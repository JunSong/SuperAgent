package com.superagent.gateway;

import com.sun.net.httpserver.HttpServer;
import com.superagent.core.chat.RunCredential;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.gateway.GatewayAccount;
import com.superagent.core.gateway.GatewayAccountType;
import com.superagent.core.gateway.GatewayAttempt;
import com.superagent.core.gateway.GatewayAttemptStatus;
import com.superagent.core.gateway.GatewayLedgerEntry;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayQuotaInsufficientException;
import com.superagent.core.gateway.GatewayQuotaMode;
import com.superagent.core.gateway.ModelRunLease;
import com.superagent.core.tenant.TenantContext;
import com.superagent.gateway.count.InputTokenCounterService;
import com.superagent.platform.provider.CredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Wire-in: grant/quota gates run before vendor count (review C3 / Minor wire coverage). */
class UpstreamProxyCountGateTest {

    private HttpServer stub;
    private final AtomicInteger countHits = new AtomicInteger();
    private final AtomicInteger upstreamHits = new AtomicInteger();
    private String anthropicBase;

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/anthropic/v1/messages/count_tokens", exchange -> {
            countHits.incrementAndGet();
            byte[] body = "{\"input_tokens\":10}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.createContext("/anthropic/v1/messages", exchange -> {
            upstreamHits.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        anthropicBase = "http://127.0.0.1:" + stub.getAddress().getPort() + "/anthropic";
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    @Test
    void exhaustedQuotaSkipsVendorCountAndUpstream() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        MemoryMetering ledger = new MemoryMetering();
        ledger.putEmployee("2026-11", "alice", 0);

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", anthropicBase, "sk", true)));

        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        HttpClient http = HttpClient.newHttpClient();
        UpstreamProxy proxy = new UpstreamProxy(
                credentials, http, clock, new QuotaReserveGate(clock),
                GatewayProxyTimeouts.TESTS, new InputTokenCounterService(http));
        proxy.setMetering(ledger);
        proxy.setGrants(grants);

        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t1", "r1", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        byte[] body = "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":8}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> proxy.forward(grants.require("Bearer " + lease.token()), "anthropic",
                "/v1/messages", "POST", Map.of(), body, new ByteArrayOutputStream(), (c, ct) -> {
                }))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("额度已用尽");
        assertThat(countHits.get()).isZero();
        assertThat(upstreamHits.get()).isZero();
    }

    @Test
    void hardQuotaCliRefusesWhenVendorCountFails() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        MemoryMetering ledger = new MemoryMetering();
        ledger.putEmployee("2026-11", "alice", 10_000);

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(
                        new RunCredential("k1", anthropicBase + "-missing", "sk", true)));

        GatewayPolicyService policies = mock(GatewayPolicyService.class);
        when(policies.get()).thenReturn(new com.superagent.core.gateway.GatewayPolicy(
                1, 2, 500_000L, 100_000L,
                com.superagent.core.gateway.GatewayReportMode.LEGACY,
                List.of("deepseek"),
                List.of("deepseek")));

        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        HttpClient http = HttpClient.newHttpClient();
        UpstreamProxy proxy = new UpstreamProxy(
                credentials, http, clock, new QuotaReserveGate(clock),
                GatewayProxyTimeouts.TESTS, new InputTokenCounterService(http));
        proxy.setMetering(ledger);
        proxy.setGrants(grants);
        proxy.setPolicyService(policies);

        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t1", "r1", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        byte[] body = "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":8}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> proxy.forward(grants.require("Bearer " + lease.token()), "anthropic",
                "/v1/messages", "POST", Map.of(), body, new ByteArrayOutputStream(), (c, ct) -> {
                }))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("硬限额");
        assertThat(upstreamHits.get()).isZero();
    }

    @Test
    void revokedGrantSkipsVendorCount() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        MemoryMetering ledger = new MemoryMetering();
        ledger.putEmployee("2026-11", "alice", 10_000);

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", anthropicBase, "sk", true)));

        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        HttpClient http = HttpClient.newHttpClient();
        UpstreamProxy proxy = new UpstreamProxy(
                credentials, http, clock, new QuotaReserveGate(clock),
                GatewayProxyTimeouts.TESTS, new InputTokenCounterService(http));
        proxy.setMetering(ledger);
        proxy.setGrants(grants);

        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t1", "r1", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        String token = lease.token();
        // Stale grant object (controller would re-require; forward rechecks live map).
        var stale = grants.require("Bearer " + token);
        TenantContext.run("co1", () -> grants.revoke(token));

        byte[] body = "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":8}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> proxy.forward(stale, "anthropic",
                "/v1/messages", "POST", Map.of(), body, new ByteArrayOutputStream(), (c, ct) -> {
                }))
                .isInstanceOf(ModelRunGrants.UnauthorizedGatewayException.class);
        assertThat(countHits.get()).isZero();
        assertThat(upstreamHits.get()).isZero();
    }

    @Test
    void revokedAfterCountBlocksReserveAndUpstream() throws IOException {
        // Count succeeds, then grant is revoked before reserve (post-count recheck).
        stub.stop(0);
        countHits.set(0);
        upstreamHits.set(0);
        AtomicReference<String> revokeToken = new AtomicReference<>();
        AtomicReference<ModelRunGrants> grantsRef = new AtomicReference<>();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/anthropic/v1/messages/count_tokens", exchange -> {
            countHits.incrementAndGet();
            String token = revokeToken.get();
            ModelRunGrants g = grantsRef.get();
            if (token != null && g != null) {
                TenantContext.run("co1", () -> g.revoke(token));
            }
            byte[] body = "{\"input_tokens\":10}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.createContext("/anthropic/v1/messages", exchange -> {
            upstreamHits.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        anthropicBase = "http://127.0.0.1:" + stub.getAddress().getPort() + "/anthropic";

        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        MemoryMetering ledger = new MemoryMetering();
        ledger.putEmployee("2026-11", "alice", 10_000);

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", anthropicBase, "sk", true)));

        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        grantsRef.set(grants);
        HttpClient http = HttpClient.newHttpClient();
        UpstreamProxy proxy = new UpstreamProxy(
                credentials, http, clock, new QuotaReserveGate(clock),
                GatewayProxyTimeouts.TESTS, new InputTokenCounterService(http));
        proxy.setMetering(ledger);
        proxy.setGrants(grants);

        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t1", "r1", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        revokeToken.set(lease.token());
        var grant = grants.require("Bearer " + lease.token());

        byte[] body = "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":8}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> proxy.forward(grant, "anthropic",
                "/v1/messages", "POST", Map.of(), body, new ByteArrayOutputStream(), (c, ct) -> {
                }))
                .isInstanceOf(ModelRunGrants.UnauthorizedGatewayException.class);
        assertThat(countHits.get()).isEqualTo(1);
        assertThat(upstreamHits.get()).isZero();
    }

    static final class MemoryMetering implements GatewayMetering {
        private final Map<String, AtomicLong> available = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> reserved = new ConcurrentHashMap<>();
        private final Map<String, GatewayAttempt> attempts = new ConcurrentHashMap<>();

        private static String key(String period, String user) {
            return period + "|" + user;
        }

        @Override
        public void putEmployee(String period, String userId, long avail) {
            available.put(key(period, userId), new AtomicLong(avail));
            reserved.put(key(period, userId), new AtomicLong(0));
        }

        @Override
        public void putEmployee(String period, String userId, long avail, long settled, long baseQuota, long floatAllowance) {
            putEmployee(period, userId, avail);
        }

        @Override
        public void putPool(String period, GatewayAccountType type, long avail) {
        }

        @Override
        public Optional<GatewayAccount> findEmployee(String period, String userId) {
            return findAccount(period, GatewayAccountType.EMPLOYEE, userId);
        }

        @Override
        public Optional<GatewayAccount> findAccount(String period, GatewayAccountType type, String ownerId) {
            AtomicLong a = available.get(key(period, ownerId));
            if (a == null) {
                return Optional.empty();
            }
            long avail = a.get();
            return Optional.of(new GatewayAccount(period, type, ownerId, GatewayQuotaMode.LIMITED,
                    avail, reserved.get(key(period, ownerId)).get(), 0, avail, 0, null));
        }

        @Override
        public GatewayAttempt reserve(ReserveRequest request) {
            String k = key(request.period(), request.payerUserId());
            AtomicLong avail = available.get(k);
            if (avail == null || avail.get() < request.reservedTokens()) {
                throw new GatewayQuotaInsufficientException("QUOTA_INSUFFICIENT");
            }
            avail.addAndGet(-request.reservedTokens());
            reserved.get(k).addAndGet(request.reservedTokens());
            String id = UUID.randomUUID().toString();
            GatewayAttempt attempt = new GatewayAttempt(id, request.requestId(), 1, request.turnId(), request.runId(),
                    request.payerUserId(), request.cli(), request.model(), request.period(),
                    request.reservedTokens(), 0, GatewayAttemptStatus.RESERVED);
            attempts.put(id, attempt);
            return attempt;
        }

        @Override
        public boolean beginSend(String attemptId) {
            return true;
        }

        @Override
        public GatewayAttempt settle(String attemptId, long actualTokens) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GatewayAttempt markPending(String attemptId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(String attemptId) {
            GatewayAttempt a = attempts.remove(attemptId);
            if (a == null || a.status() != GatewayAttemptStatus.RESERVED) {
                return;
            }
            String k = key(a.period(), a.payerUserId());
            available.get(k).addAndGet(a.reservedTokens());
            reserved.get(k).addAndGet(-a.reservedTokens());
        }

        @Override
        public GatewayAccount allocate(String operationId, String period, String toUserId, long amount, String actorUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GatewayAccount setFloatAllowance(String period, String userId, long floatAllowance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int applyDefaultFloat(String period, long floatAllowance) {
            return 0;
        }

        @Override
        public GatewayAccount reclaim(String operationId, String period, String fromUserId, long amount, String actorUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void transfer(String operationId, String period, String fromUserId, String toUserId, long amount,
                             String actorUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GatewayLedgerEntry> listLedger(String period, int limit) {
            return List.of();
        }
    }
}
