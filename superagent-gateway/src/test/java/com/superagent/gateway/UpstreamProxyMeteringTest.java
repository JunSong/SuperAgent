package com.superagent.gateway;

import com.sun.net.httpserver.HttpServer;
import com.superagent.core.chat.RunCredential;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Proxy refuses a second reservation that cannot fit while the first freeze is held (0 outbound). */
class UpstreamProxyMeteringTest {

    private HttpServer stub;
    private final AtomicInteger stubHits = new AtomicInteger();
    private String stubBase;

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/anthropic/v1/messages", exchange -> {
            stubHits.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        stubBase = "http://127.0.0.1:" + stub.getAddress().getPort() + "/anthropic";
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    @Test
    void secondReserveFailsWithZeroOutboundWhileFirstHeld() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        MemoryMetering ledger = new MemoryMetering();
        ledger.putEmployee("2026-11", "alice", 400);

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", stubBase, "sk", false)));

        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient(), clock);
        proxy.setMetering(ledger);

        byte[] body = "{\"max_tokens\":10}".getBytes(StandardCharsets.UTF_8);
        long r = ReservationEstimator.estimate(body);

        ModelRunLease lease1 = TenantContext.call("co1",
                () -> grants.issue("t1", "r1", "alice", "deepseek", "m", Duration.ofMinutes(5)));
        int status = proxy.forward(grants.require("Bearer " + lease1.token()), "anthropic", "/v1/messages",
                "POST", Map.of(), body, new ByteArrayOutputStream(), c -> {
                });
        assertThat(status).isEqualTo(200);
        assertThat(stubHits.get()).isEqualTo(1);
        assertThat(ledger.findEmployee("2026-11", "alice").orElseThrow().available()).isEqualTo(400 - r);

        ledger.putEmployee("2026-11", "alice", r - 1);

        ModelRunLease lease2 = TenantContext.call("co1",
                () -> grants.issue("t2", "r2", "alice", "deepseek", "m", Duration.ofMinutes(5)));
        assertThatThrownBy(() -> proxy.forward(grants.require("Bearer " + lease2.token()), "anthropic",
                "/v1/messages", "POST", Map.of(), body, new ByteArrayOutputStream(), c -> {
                }))
                .isInstanceOf(GatewayQuotaInsufficientException.class);
        assertThat(stubHits.get()).isEqualTo(1);
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
            GatewayAttempt a = attempts.get(attemptId);
            if (a == null || a.status() != GatewayAttemptStatus.RESERVED) {
                return false;
            }
            attempts.put(attemptId, new GatewayAttempt(a.id(), a.requestId(), a.attemptNo(), a.turnId(), a.runId(),
                    a.payerUserId(), a.cli(), a.model(), a.period(), a.reservedTokens(), a.actualTokens(),
                    GatewayAttemptStatus.SENDING));
            return true;
        }

        @Override
        public GatewayAttempt settle(String attemptId, long actualTokens) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GatewayAttempt markPending(String attemptId) {
            GatewayAttempt a = attempts.get(attemptId);
            GatewayAttempt pending = new GatewayAttempt(a.id(), a.requestId(), a.attemptNo(), a.turnId(), a.runId(),
                    a.payerUserId(), a.cli(), a.model(), a.period(), a.reservedTokens(), a.actualTokens(),
                    GatewayAttemptStatus.PENDING_RECONCILIATION);
            attempts.put(attemptId, pending);
            return pending;
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
