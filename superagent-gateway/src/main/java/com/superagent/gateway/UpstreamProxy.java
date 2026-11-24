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

    private final CredentialService credentials;
    private final HttpClient http;
    private final Clock clock;
    private final QuotaReserveGate reserveGate;
    private GatewayMetering metering; // null in tests / legacy: no ledger

    @Autowired
    public UpstreamProxy(CredentialService credentials, QuotaReserveGate reserveGate) {
        this(credentials, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), Clock.systemUTC(),
                reserveGate);
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

    /**
     * @param protocol path segment ({@code anthropic} / {@code openai})
     * @param suffix   path after {@code /model/{protocol}}, e.g. {@code /v1/messages}
     * @param requestHeaders inbound headers (auth stripped by caller)
     * @param body     full request body (bounded by the controller)
     * @param out      response body stream to the CLI
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
            java.util.function.IntConsumer onResponseHeaders) throws IOException, InterruptedException {
        try {
            return TenantContext.call(grant.companyId(), () -> {
                GatewayAttempt attempt = null;
                boolean sending = false;
                try {
                    attempt = openAttempt(grant, body);

                    CredentialService.Choice choice = credentials.current(grant.cli());
                    RunCredential cred = choice.credential();
                    if (cred == null || cred.apiKey() == null || cred.apiKey().isBlank()) {
                        if (attempt != null) {
                            metering.cancel(attempt.id());
                        }
                        throw new ForbiddenException("本公司还没有配置 " + grant.cli() + " 的 API Key");
                    }
                    String upstreamBase = resolveUpstreamBase(grant.cli(), cred.baseUrl());
                    String target = joinUrl(upstreamBase, suffix);
                    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(target))
                            .timeout(Duration.ofMinutes(30));
                    applyMethod(req, method, body);
                    copyForwardHeaders(requestHeaders, req);
                    applyCompanyAuth(req, protocol, cred);
                    log.debug("gateway forward company={} cli={} run={} → {}", grant.companyId(), grant.cli(),
                            grant.runId(), redact(target));

                    if (attempt != null) {
                        if (!metering.beginSend(attempt.id())) {
                            metering.cancel(attempt.id());
                            throw new ForbiddenException("无法取得发送权：预占已失效");
                        }
                        sending = true;
                    }

                    HttpResponse<InputStream> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
                    if (onResponseHeaders != null) {
                        onResponseHeaders.accept(resp.statusCode());
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
                    if (attempt != null && !sending) {
                        try {
                            metering.cancel(attempt.id());
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

    private GatewayAttempt openAttempt(ModelRunGrant grant, byte[] body) {
        if (metering == null) {
            return null;
        }
        String period = GatewayPeriods.current(clock);
        var account = metering.findEmployee(period, grant.payerUserId());
        if (account.isEmpty() || account.get().quotaMode() == GatewayQuotaMode.UNLIMITED) {
            return null; // legacy / unlimited: no ledger gate
        }
        long r = ReservationEstimator.estimate(body);
        return reserveGate.reserve(metering, new GatewayMetering.ReserveRequest(
                UUID.randomUUID().toString(),
                grant.turnId(),
                grant.runId(),
                grant.payerUserId(),
                grant.cli(),
                grant.model(),
                period,
                r));
    }

    /** Prefer settle(A) when usage is present; otherwise keep the freeze as pending (#34). */
    private void settleOrPending(GatewayAttempt attempt, StreamingUsageProbe usageProbe) {
        if (attempt == null || metering == null) {
            return;
        }
        var total = usageProbe.total();
        if (total.isPresent()) {
            metering.settle(attempt.id(), total.getAsLong());
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
        return suffix.startsWith("/") ? b + suffix : b + "/" + suffix;
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
            if (HOP_BY_HOP.contains(lower) || lower.equals("authorization") || lower.equals("x-api-key")
                    || lower.equals("x-mcp-token")) {
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
        return url;
    }
}
