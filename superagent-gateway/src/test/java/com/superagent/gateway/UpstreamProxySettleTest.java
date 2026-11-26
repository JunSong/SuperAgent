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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** When the upstream stream carries usage, the proxy settles A and refunds R−A. */
class UpstreamProxySettleTest {

    private HttpServer stub;
    private String stubBase;

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/openai/v1/chat/completions", exchange -> {
            byte[] body = """
                    data: {"choices":[{"delta":{"content":"hi"}}]}

                    data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.createContext("/openai/v1/responses", exchange -> {
            // Incomplete Responses stream: usage never finalized → pending (R06).
            byte[] body = "data: {\"type\":\"response.created\"}\n\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        stubBase = "http://127.0.0.1:" + stub.getAddress().getPort() + "/openai";
    }

    @AfterEach
    void stop() {
        stub.stop(0);
    }

    @Test
    void settlesWhenUsagePresent() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        SettleMetering ledger = new SettleMetering();
        ledger.putEmployee("2026-11", "alice", 10_000);

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", stubBase, "sk", false)));

        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient(), clock);
        proxy.setMetering(ledger);

        byte[] reqBody = "{\"model\":\"deepseek-chat\",\"max_tokens\":100}".getBytes(StandardCharsets.UTF_8);
        long r = ReservationEstimator.estimate(reqBody);
        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t1", "r1", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));

        int status = proxy.forward(grants.require("Bearer " + lease.token()), "openai", "/v1/chat/completions",
                "POST", Map.of(), reqBody, new ByteArrayOutputStream(), (c, ct) -> {
                });
        assertThat(status).isEqualTo(200);
        assertThat(ledger.lastSettledA).isEqualTo(15);
        assertThat(ledger.findEmployee("2026-11", "alice")).hasValueSatisfying(a -> {
            assertThat(a.reserved()).isZero();
            assertThat(a.settled()).isEqualTo(15);
            assertThat(a.available()).isEqualTo(10_000 - 15);
        });
        assertThat(r).isGreaterThan(15);
    }

    @Test
    void marksPendingWhenUsageMissing() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        SettleMetering ledger = new SettleMetering();
        ledger.putEmployee("2026-11", "alice", 10_000);

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", stubBase, "sk", false)));

        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient(), clock);
        proxy.setMetering(ledger);

        byte[] reqBody = "{\"max_tokens\":10}".getBytes(StandardCharsets.UTF_8);
        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t2", "r2", "alice", "deepseek", "m", Duration.ofMinutes(5)));

        proxy.forward(grants.require("Bearer " + lease.token()), "openai", "/v1/responses",
                "POST", Map.of(), reqBody, new ByteArrayOutputStream(), (c, ct) -> {
                });
        assertThat(ledger.lastSettledA).isEqualTo(-1);
        assertThat(ledger.pending).isTrue();
        assertThat(ledger.findEmployee("2026-11", "alice")).hasValueSatisfying(a -> assertThat(a.reserved()).isPositive());
    }

    /** Minimal ledger that records settle/pending for proxy wiring. */
    static final class SettleMetering implements GatewayMetering {
        private final Map<String, AtomicLong> available = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> reserved = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> settled = new ConcurrentHashMap<>();
        private final Map<String, GatewayAttempt> attempts = new ConcurrentHashMap<>();
        long lastSettledA = -1;
        boolean pending;

        private static String key(String period, String user) {
            return period + "|" + user;
        }

        @Override
        public void putEmployee(String period, String userId, long avail) {
            available.put(key(period, userId), new AtomicLong(avail));
            reserved.put(key(period, userId), new AtomicLong(0));
            settled.put(key(period, userId), new AtomicLong(0));
        }

        @Override
        public void putEmployee(String period, String userId, long avail, long settledTokens, long baseQuota,
                                long floatAllowance) {
            putEmployee(period, userId, avail);
            settled.put(key(period, userId), new AtomicLong(settledTokens));
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
                    avail, reserved.get(key(period, ownerId)).get(), settled.get(key(period, ownerId)).get(),
                    avail + reserved.get(key(period, ownerId)).get() + settled.get(key(period, ownerId)).get(),
                    0, null));
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
            attempts.put(attemptId, withStatus(a, GatewayAttemptStatus.SENDING));
            return true;
        }

        @Override
        public GatewayAttempt settle(String attemptId, long actualTokens) {
            GatewayAttempt a = attempts.get(attemptId);
            long r = a.reservedTokens();
            long employeeCharge = Math.min(actualTokens, r);
            long refund = r - employeeCharge;
            String k = key(a.period(), a.payerUserId());
            available.get(k).addAndGet(refund);
            reserved.get(k).addAndGet(-r);
            settled.get(k).addAndGet(employeeCharge);
            lastSettledA = actualTokens; // full A
            GatewayAttempt done = new GatewayAttempt(a.id(), a.requestId(), a.attemptNo(), a.turnId(), a.runId(),
                    a.payerUserId(), a.cli(), a.model(), a.period(), r, actualTokens, GatewayAttemptStatus.SETTLED);
            attempts.put(attemptId, done);
            return done;
        }

        @Override
        public GatewayAttempt markPending(String attemptId) {
            pending = true;
            GatewayAttempt a = attempts.get(attemptId);
            GatewayAttempt p = withStatus(a, GatewayAttemptStatus.PENDING_RECONCILIATION);
            attempts.put(attemptId, p);
            return p;
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

        private static GatewayAttempt withStatus(GatewayAttempt a, GatewayAttemptStatus status) {
            return new GatewayAttempt(a.id(), a.requestId(), a.attemptNo(), a.turnId(), a.runId(),
                    a.payerUserId(), a.cli(), a.model(), a.period(), a.reservedTokens(), a.actualTokens(), status);
        }
    }
}
