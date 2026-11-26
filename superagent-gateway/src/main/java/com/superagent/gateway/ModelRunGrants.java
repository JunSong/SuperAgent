package com.superagent.gateway;

import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayPeriods;
import com.superagent.core.gateway.ModelRunLease;
import com.superagent.core.gateway.ModelRunLeaseService;
import com.superagent.core.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * In-memory model-run tokens (same lifecycle idea as MCP grants): issued per CLI process, revoked when the
 * run ends, never shared across companies. Single control-plane JVM only.
 * Managed-CLI membership comes from company {@link GatewayPolicyService} (admin UI), not process env alone.
 * Expired / revoked entries are swept on a timer so the map cannot grow without bound.
 */
@Component
public class ModelRunGrants implements ModelRunLeaseService {

    private static final Logger log = LoggerFactory.getLogger(ModelRunGrants.class);

    public static final String PROTOCOL_ANTHROPIC = "anthropic";
    public static final String PROTOCOL_OPENAI = "openai";

    private final Map<String, ModelRunGrant> byToken = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Predicate<String> managedCheck;
    private final boolean configuredSelfUrl;
    private final boolean sweepEnabled;
    private final long sweepIntervalSeconds;
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gateway-run-token-sweep");
        t.setDaemon(true);
        return t;
    });
    private volatile String selfBase;
    private GatewayMetering metering; // null in early tests: managed CLIs are not assignable to payers

    @Autowired
    public ModelRunGrants(
            GatewayPolicyService policy,
            @Value("${superagent.gateway.self-url:}") String selfUrl,
            @Value("${server.port:18080}") int port,
            Clock clock,
            @Value("${superagent.gateway.token-sweep.enabled:true}") boolean sweepEnabled,
            @Value("${superagent.gateway.token-sweep.interval-seconds:60}") long sweepIntervalSeconds) {
        this(policy::managed, selfUrl, port, clock, selfUrl != null && !selfUrl.isBlank(),
                sweepEnabled, sweepIntervalSeconds);
    }

    @Autowired(required = false)
    void setMetering(GatewayMetering metering) {
        this.metering = metering;
    }

    /** Test / explicit wiring with a fixed managed set (no scheduled sweep). */
    public ModelRunGrants(Set<String> managedClis, String selfBase, Clock clock) {
        Set<String> fixed = Set.copyOf(managedClis);
        this.clock = clock;
        this.managedCheck = cli -> cli != null && fixed.contains(cli.toLowerCase(Locale.ROOT));
        this.configuredSelfUrl = true;
        this.selfBase = trimSlash(selfBase);
        this.sweepEnabled = false;
        this.sweepIntervalSeconds = 60;
    }

    private ModelRunGrants(Predicate<String> managedCheck, String selfUrl, int port, Clock clock,
                           boolean configuredSelfUrl, boolean sweepEnabled, long sweepIntervalSeconds) {
        this.clock = clock;
        this.managedCheck = managedCheck;
        this.configuredSelfUrl = configuredSelfUrl;
        this.selfBase = configuredSelfUrl ? trimSlash(selfUrl.trim()) : "http://127.0.0.1:" + port;
        this.sweepEnabled = sweepEnabled;
        this.sweepIntervalSeconds = Math.max(5, sweepIntervalSeconds);
    }

    @EventListener
    public void onWebServer(WebServerInitializedEvent event) {
        if (!configuredSelfUrl && event.getWebServer().getPort() > 0) {
            selfBase = "http://127.0.0.1:" + event.getWebServer().getPort();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void startSweep() {
        if (!sweepEnabled) {
            return;
        }
        sweeper.scheduleWithFixedDelay(this::sweepSafely, sweepIntervalSeconds, sweepIntervalSeconds, TimeUnit.SECONDS);
        log.info("gateway run-token sweep started (interval={}s)", sweepIntervalSeconds);
    }

    @PreDestroy
    void stopSweep() {
        sweeper.shutdownNow();
    }

    /**
     * Drop expired and revoked tokens from the in-memory map. Returns how many entries were removed.
     */
    int sweepExpired() {
        Instant now = clock.instant();
        int[] removed = {0};
        byToken.entrySet().removeIf(e -> {
            if (e.getValue().usable(now)) {
                return false;
            }
            removed[0]++;
            return true;
        });
        return removed[0];
    }

    private void sweepSafely() {
        try {
            int n = sweepExpired();
            if (n > 0) {
                log.debug("gateway run-token sweep removed={}", n);
            }
        } catch (RuntimeException e) {
            log.warn("gateway run-token sweep failed: {}", e.getMessage());
        }
    }

    @Override
    public boolean managed(String cli) {
        return managedCheck.test(cli);
    }

    @Override
    public boolean payerMayUse(String cli, String payerUserId) {
        if (!managed(cli)) {
            return true;
        }
        if (metering == null || payerUserId == null || payerUserId.isBlank()) {
            return false;
        }
        String period = GatewayPeriods.current(clock);
        return metering.findEmployee(period, payerUserId)
                .filter(a -> a.baseQuota() > 0 || a.available() > 0)
                .isPresent();
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
