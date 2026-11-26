package com.superagent.gateway;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.gateway.GatewayAttempt;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayPeriods;
import com.superagent.core.gateway.GatewayQuotaMode;
import com.superagent.core.provider.ProviderCatalog;
import com.superagent.core.tenant.TenantContext;
import com.superagent.platform.provider.CredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Forwards a model HTTP call to the company upstream with the real Key swapped in.
 * Streams the response body; never buffers the whole reply. When {@link GatewayMetering} is present
 * and the payer has a LIMITED account, reserves tokens before the upstream call (#34 PR-03).
 */
@Component
public class UpstreamProxy {

    private static final Logger log = LoggerFactory.getLogger(UpstreamProxy.class);
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length");
    /**
     * Explicit allow-list only (C3): never forward Cookie / session / internal auth to the vendor.
     * {@code anthropic-version} is set by {@link #applyCompanyAuth} only — forwarding the CLI's
     * copy would duplicate the header (JDK {@code header()} appends).
     */
    private static final Set<String> FORWARD_HEADERS = Set.of(
            "content-type", "accept", "anthropic-beta", "openai-beta");

    private final CredentialService credentials;
    private final HttpClient http;
    private final Clock clock;
    private final QuotaReserveGate reserveGate;
    private GatewayMetering metering; // null in tests / legacy: no ledger
    private ModelRunGrants grants; // null in some unit tests: skip live-token recheck

    @Autowired
    public UpstreamProxy(CredentialService credentials, QuotaReserveGate reserveGate, Clock clock) {
        this(credentials, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), clock, reserveGate);
    }

    /** Tests inject a client pointed at a local stub. */
    public UpstreamProxy(CredentialService credentials, HttpClient http) {
        this(credentials, http, Clock.systemUTC());
    }

    public UpstreamProxy(CredentialService credentials, HttpClient http, Clock clock) {
        this(credentials, http, clock, new QuotaReserveGate(clock));
    }

    public UpstreamProxy(CredentialService credentials, HttpClient http, Clock clock, QuotaReserveGate reserveGate) {
        this.credentials = credentials;
        this.http = http;
        this.clock = clock;
        this.reserveGate = reserveGate != null ? reserveGate : new QuotaReserveGate(clock);
    }

    @Autowired(required = false)
    void setMetering(GatewayMetering metering) {
        this.metering = metering;
    }

    @Autowired(required = false)
    void setGrants(ModelRunGrants grants) {
        this.grants = grants;
    }

    /**
     * @param protocol path segment ({@code anthropic} / {@code openai})
     * @param suffix   path after {@code /model/{protocol}}, e.g. {@code /v1/messages}
     * @param requestHeaders inbound headers (auth stripped by caller)
     * @param body     full request body (bounded by the controller)
     * @param out      response body stream to the CLI
     * @param onResponseHeaders status + upstream Content-Type (nullable type)
     * @return upstream status code
     */
    public int forward(
            ModelRunGrant grant,
            String protocol,
            String suffix,
            String method,
            java.util.Map<String, String> requestHeaders,
            byte[] body,
            OutputStream out,
            java.util.function.BiConsumer<Integer, String> onResponseHeaders) throws IOException, InterruptedException {
        String normalizedSuffix = GatewayRoutePolicy.normalizeSuffix(suffix);
        GatewayRoutePolicy.assertAllowed(protocol, method, normalizedSuffix, grant.model(), body);
        ReservationEstimator.Prepared prepared = ReservationEstimator.prepare(body, protocol, normalizedSuffix);
        try {
            return TenantContext.call(grant.companyId(), () -> {
                GatewayAttempt attempt = null;
                boolean sending = false;
                try {
                    attempt = openAttempt(grant, prepared.reservedTokens());
                    requireGrantStillUsable(grant, attempt);

                    CredentialService.Choice choice = credentials.current(grant.cli());
                    RunCredential cred = choice.credential();
                    if (cred == null || cred.apiKey() == null || cred.apiKey().isBlank()) {
                        if (attempt != null) {
                            metering.cancel(attempt.id());
                        }
                        throw new ForbiddenException("本公司还没有配置 " + grant.cli() + " 的 API Key");
                    }
                    String upstreamBase = resolveUpstreamBase(grant.cli(), cred.baseUrl());
                    String target = joinUrl(upstreamBase, normalizedSuffix);
                    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(target))
                            .timeout(Duration.ofMinutes(30));
                    applyMethod(req, method, prepared.body());
                    copyForwardHeaders(requestHeaders, req);
                    applyCompanyAuth(req, protocol, cred);
                    log.debug("gateway forward company={} cli={} run={} → {}", grant.companyId(), grant.cli(),
                            grant.runId(), redact(target));

                    if (attempt != null) {
                        requireGrantStillUsable(grant, attempt);
                        if (!metering.beginSend(attempt.id())) {
                            metering.cancel(attempt.id());
                            throw new ForbiddenException("无法取得发送权：预占已失效");
                        }
                        sending = true;
                    }

                    requireGrantStillUsable(grant, attempt);
                    HttpResponse<InputStream> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
                    if (onResponseHeaders != null) {
                        onResponseHeaders.accept(resp.statusCode(),
                                resp.headers().firstValue("content-type").orElse(null));
                    }
                    StreamingUsageProbe usageProbe = new StreamingUsageProbe();
                    try (InputStream in = resp.body()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            if (n == 0) {
                                continue;
                            }
                            out.write(buf, 0, n);
                            out.flush(); // SSE: push each chunk; do not sit on the servlet buffer
                            usageProbe.accept(buf, 0, n);
                        }
                        out.flush();
                    }
                    usageProbe.finish();
                    settleOrPending(attempt, usageProbe);
                    return resp.statusCode();
                } catch (IOException e) {
                    if (attempt != null) {
                        if (sending) {
                            metering.markPending(attempt.id());
                        } else {
                            metering.cancel(attempt.id());
                        }
                    }
                    throw new java.io.UncheckedIOException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (attempt != null) {
                        if (sending) {
                            metering.markPending(attempt.id());
                        } else {
                            metering.cancel(attempt.id());
                        }
                    }
                    throw new IllegalStateException("upstream interrupted", e);
                } catch (RuntimeException e) {
                    if (attempt != null) {
                        try {
                            if (sending) {
                                metering.markPending(attempt.id());
                            } else {
                                metering.cancel(attempt.id());
                            }
                        } catch (RuntimeException ignored) {
                            // keep freeze
                        }
                    }
                    throw e;
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof InterruptedException ie) {
                throw ie;
            }
            throw e;
        }
    }

    private void requireGrantStillUsable(ModelRunGrant grant, GatewayAttempt attempt) {
        if (grants == null) {
            if (!grant.usable(clock.instant())) {
                if (attempt != null && metering != null) {
                    metering.cancel(attempt.id());
                }
                throw new ModelRunGrants.UnauthorizedGatewayException("invalid or revoked run token");
            }
            return;
        }
        // Re-read the map entry: revoke() replaces the record; the caller's copy stays stale (C6).
        var live = grants.find(grant.token()).filter(g -> g.usable(clock.instant()));
        if (live.isEmpty()) {
            if (attempt != null && metering != null) {
                try {
                    metering.cancel(attempt.id());
                } catch (RuntimeException ignored) {
                    // best-effort
                }
            }
            throw new ModelRunGrants.UnauthorizedGatewayException("invalid or revoked run token");
        }
    }

    private GatewayAttempt openAttempt(ModelRunGrant grant, long reservedTokens) {
        if (metering == null) {
            return null;
        }
        String period = GatewayPeriods.current(clock);
        var account = metering.findEmployee(period, grant.payerUserId());
        // Missing account is not unlimited (R02 / C4): refuse with zero outbound.
        if (account.isEmpty()) {
            throw new ForbiddenException("本月尚未分配模型额度，无法调用受管网关");
        }
        if (account.get().quotaMode() == GatewayQuotaMode.UNLIMITED) {
            return null; // explicit unlimited: no ledger gate
        }
        return reserveGate.reserve(metering, new GatewayMetering.ReserveRequest(
                UUID.randomUUID().toString(),
                grant.turnId(),
                grant.runId(),
                grant.payerUserId(),
                grant.cli(),
                grant.model(),
                period,
                reservedTokens));
    }

    /** Prefer settle(A) when usage is present and the stream completed; otherwise keep the freeze as pending. */
    private void settleOrPending(GatewayAttempt attempt, StreamingUsageProbe usageProbe) {
        if (attempt == null || metering == null) {
            return;
        }
        var total = usageProbe.total();
        if (total.isPresent()) {
            long a = total.getAsLong();
            try {
                metering.settle(attempt.id(), a);
            } catch (RuntimeException e) {
                log.warn("gateway settle failed attempt={}: {}", attempt.id(), e.getMessage());
                metering.markPending(attempt.id(), a);
            }
        } else {
            metering.markPending(attempt.id());
        }
    }

    static String resolveUpstreamBase(String cli, String credentialBaseUrl) {
        if (credentialBaseUrl != null && !credentialBaseUrl.isBlank()) {
            return trimSlash(credentialBaseUrl);
        }
        return ProviderCatalog.find(cli)
                .map(e -> e.access().defaultBaseUrl())
                .map(UpstreamProxy::trimSlash)
                .orElseThrow(() -> new IllegalStateException("no default base URL for " + cli));
    }

    static String joinUrl(String base, String suffix) {
        String b = trimSlash(base);
        if (suffix == null || suffix.isBlank() || "/".equals(suffix)) {
            return b;
        }
        String s = suffix.startsWith("/") ? suffix : "/" + suffix;
        // OpenAI-compatible defaultBaseUrl already ends with /v1; CLI may still send /v1/... → strip one.
        if (b.endsWith("/v1") && s.startsWith("/v1/")) {
            s = s.substring(3);
        }
        return b + s;
    }

    private static void applyMethod(HttpRequest.Builder req, String method, byte[] body) {
        String m = method == null ? "POST" : method.toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        switch (m) {
            case "GET" -> req.GET();
            case "DELETE" -> req.DELETE();
            case "PUT" -> req.PUT(pub);
            case "PATCH" -> req.method("PATCH", pub);
            default -> req.POST(pub);
        }
    }

    private static void copyForwardHeaders(java.util.Map<String, String> inbound, HttpRequest.Builder req) {
        if (inbound == null) {
            return;
        }
        for (var e : inbound.entrySet()) {
            String name = e.getKey();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || !FORWARD_HEADERS.contains(lower)) {
                continue;
            }
            req.header(name, e.getValue());
        }
    }

    private static void applyCompanyAuth(HttpRequest.Builder req, String protocol, RunCredential cred) {
        if (ModelRunGrants.PROTOCOL_ANTHROPIC.equals(protocol)) {
            if (cred.bearer()) {
                req.header("Authorization", "Bearer " + cred.apiKey());
            } else {
                req.header("x-api-key", cred.apiKey());
            }
            req.header("anthropic-version", "2023-06-01");
            return;
        }
        if (cred.bearer() || ModelRunGrants.PROTOCOL_OPENAI.equals(protocol)) {
            req.header("Authorization", "Bearer " + cred.apiKey());
        } else {
            req.header("x-api-key", cred.apiKey());
        }
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String redact(String url) {
        if (url == null) {
            return "";
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "[redacted]";
        }
        int path = url.indexOf('/', scheme + 3);
        return path < 0 ? url.substring(0, scheme + 3) + "…" : url.substring(0, path) + "/…";
    }
}
