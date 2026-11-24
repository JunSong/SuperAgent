package com.superagent.gateway;

import com.superagent.core.gateway.ModelRunLease;
import com.superagent.core.gateway.ModelRunLeaseService;
import com.superagent.core.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory model-run tokens (same lifecycle idea as MCP grants): issued per CLI process, revoked when the
 * run ends, never shared across companies. Single control-plane JVM only.
 */
@Component
public class ModelRunGrants implements ModelRunLeaseService {

    public static final String PROTOCOL_ANTHROPIC = "anthropic";
    public static final String PROTOCOL_OPENAI = "openai";

    private final Map<String, ModelRunGrant> byToken = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Set<String> managedClis;
    private final boolean configuredSelfUrl;
    private volatile String selfBase;

    @Autowired
    public ModelRunGrants(
            @Value("${superagent.gateway.managed-clis:}") String managedClis,
            @Value("${superagent.gateway.self-url:}") String selfUrl,
            @Value("${server.port:18080}") int port,
            Clock clock) {
        this.clock = clock;
        this.managedClis = Arrays.stream(managedClis.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.configuredSelfUrl = selfUrl != null && !selfUrl.isBlank();
        this.selfBase = configuredSelfUrl ? trimSlash(selfUrl.trim()) : "http://127.0.0.1:" + port;
    }

    /** Test / explicit wiring. */
    public ModelRunGrants(Set<String> managedClis, String selfBase, Clock clock) {
        this.clock = clock;
        this.managedClis = Set.copyOf(managedClis);
        this.configuredSelfUrl = true;
        this.selfBase = trimSlash(selfBase);
    }

    @EventListener
    public void onWebServer(WebServerInitializedEvent event) {
        if (!configuredSelfUrl && event.getWebServer().getPort() > 0) {
            selfBase = "http://127.0.0.1:" + event.getWebServer().getPort();
        }
    }

    @Override
    public boolean managed(String cli) {
        return cli != null && managedClis.contains(cli.toLowerCase(Locale.ROOT));
    }

    @Override
    public ModelRunLease issue(String turnId, String runId, String payerUserId, String cli, String model, Duration ttl) {
        String companyId = TenantContext.require();
        if (turnId == null || turnId.isBlank() || runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("turnId and runId are required");
        }
        if (payerUserId == null || payerUserId.isBlank() || cli == null || cli.isBlank()) {
            throw new IllegalArgumentException("payerUserId and cli are required");
        }
        Duration life = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofHours(1) : ttl;
        String token = newToken();
        Instant exp = clock.instant().plus(life);
        ModelRunGrant grant = new ModelRunGrant(token, companyId, payerUserId, turnId, runId, cli,
                model == null ? "" : model, exp, false);
        byToken.put(token, grant);
        return new ModelRunLease(token, gatewayBaseUrlFor(cli), runId, turnId, cli, grant.model());
    }

    @Override
    public void revoke(String token) {
        if (token == null) {
            return;
        }
        byToken.computeIfPresent(token, (k, g) -> g.revoke());
    }

    @Override
    public void revokeRun(String runId) {
        if (runId == null) {
            return;
        }
        byToken.replaceAll((k, g) -> runId.equals(g.runId()) ? g.revoke() : g);
    }

    /** Resolves a Bearer (or raw) token for an inbound gateway call. */
    public ModelRunGrant require(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        if (token == null) {
            throw new UnauthorizedGatewayException("missing run token");
        }
        ModelRunGrant grant = byToken.get(token);
        if (grant == null || !grant.usable(clock.instant())) {
            throw new UnauthorizedGatewayException("invalid or revoked run token");
        }
        return grant;
    }

    public Optional<ModelRunGrant> find(String token) {
        return Optional.ofNullable(byToken.get(token));
    }

    /** How many upstream-facing grants are currently open (tests). */
    int openCount() {
        Instant now = clock.instant();
        return (int) byToken.values().stream().filter(g -> g.usable(now)).count();
    }

    String gatewayBaseUrlFor(String cli) {
        String protocol = protocolFor(cli);
        return selfBase + "/api/v1/gateway/model/" + protocol;
    }

    public static String protocolFor(String cli) {
        return com.superagent.core.provider.ProviderCatalog.find(cli)
                .map(e -> e.access().protocol())
                .filter(p -> p != null && !p.isBlank() && !"other".equals(p))
                .orElse(PROTOCOL_OPENAI);
    }

    static String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String v = authorizationHeader.trim();
        if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return v.substring(7).trim();
        }
        // Some CLIs put the opaque token where an API key would go (x-api-key).
        return v;
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Token missing, expired or revoked — controller maps to HTTP 401. */
    public static class UnauthorizedGatewayException extends RuntimeException {
        public UnauthorizedGatewayException(String message) {
            super(message);
        }
    }
}
