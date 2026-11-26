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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Review regressions: R02 fail-closed, C3 header allow-list, R01 outbound clamp, C6 revoke TOCTOU. */
class UpstreamProxyReviewFixesTest {

    private HttpServer stub;
    private final AtomicInteger stubHits = new AtomicInteger();
    private final AtomicReference<String> seenCookie = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicReference<java.util.List<String>> seenAnthropicVersions = new AtomicReference<>();
    private String stubBase;

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/anthropic/v1/messages", exchange -> {
            stubHits.incrementAndGet();
            seenCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            seenAnthropicVersions.set(exchange.getRequestHeaders().get("anthropic-version"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        stubBase = "http://127.0.0.1:" + stub.getAddress().getPort() + "/anthropic";
    }

    @AfterEach
    void stop() {
        stub.stop(0);
    }

    @Test
    void managedPayerWithoutAccountMustNotGoOutbound() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        EmptyMetering ledger = new EmptyMetering();
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", stubBase, "sk", false)));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient(), clock);
        proxy.setMetering(ledger);
        proxy.setGrants(grants);

        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t", "r", "ghost", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        assertThatThrownBy(() -> proxy.forward(grants.require("Bearer " + lease.token()), "anthropic",
                "/v1/messages", "POST", Map.of(), "{\"model\":\"deepseek-chat\"}".getBytes(StandardCharsets.UTF_8),
                new ByteArrayOutputStream(), (c, ct) -> {
                }))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("额度");
        assertThat(stubHits.get()).isZero();
    }

    @Test
    void stripsCookieAndClampsMaxTokensOnOutbound() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", stubBase, "sk", false)));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient(), clock);
        // no metering → still forward, but headers/body must be sanitized

        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t", "r", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        byte[] body = "{\"model\":\"deepseek-chat\",\"max_tokens\":256000}".getBytes(StandardCharsets.UTF_8);
        int status = proxy.forward(grants.require("Bearer " + lease.token()), "anthropic", "/v1/messages",
                "POST", Map.of("Cookie", "JSESSIONID=secret", "Content-Type", "application/json"),
                body, new ByteArrayOutputStream(), (c, ct) -> {
                });
        assertThat(status).isEqualTo(200);
        assertThat(stubHits.get()).isEqualTo(1);
        assertThat(seenCookie.get()).isNull();
        assertThat(seenBody.get()).contains("\"max_tokens\":32768");
        assertThat(seenBody.get()).doesNotContain("256000");
    }

    @Test
    void anthropicVersionIsSetOnceEvenWhenCliSendsIt() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", stubBase, "sk", false)));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient(), clock);

        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t", "r", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        byte[] body = "{\"model\":\"deepseek-chat\",\"max_tokens\":8}".getBytes(StandardCharsets.UTF_8);
        int status = proxy.forward(grants.require("Bearer " + lease.token()), "anthropic", "/v1/messages",
                "POST", Map.of("anthropic-version", "2023-06-01", "Content-Type", "application/json"),
                body, new ByteArrayOutputStream(), (c, ct) -> {
                });
        assertThat(status).isEqualTo(200);
        assertThat(seenAnthropicVersions.get()).containsExactly("2023-06-01");
    }

    @Test
    void nonJsonBodyMustNotGoOutbound() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", stubBase, "sk", false)));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient(), clock);

        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t", "r", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        assertThatThrownBy(() -> proxy.forward(grants.require("Bearer " + lease.token()), "anthropic",
                "/v1/messages", "POST", Map.of(), "not-json".getBytes(StandardCharsets.UTF_8),
                new ByteArrayOutputStream(), (c, ct) -> {
                }))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("JSON");
        assertThat(stubHits.get()).isZero();
    }

    @Test
    void revokeDuringForwardBlocksOutbound() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", stubBase, "sk", false)));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", clock);
        UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient(), clock);
        proxy.setGrants(grants);

        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t", "r", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        ModelRunGrant snapshot = grants.require("Bearer " + lease.token());
        grants.revoke(lease.token());
        assertThatThrownBy(() -> proxy.forward(snapshot, "anthropic", "/v1/messages", "POST", Map.of(),
                "{\"model\":\"deepseek-chat\"}".getBytes(StandardCharsets.UTF_8),
                new ByteArrayOutputStream(), (c, ct) -> {
                }))
                .isInstanceOf(ModelRunGrants.UnauthorizedGatewayException.class);
        assertThat(stubHits.get()).isZero();
    }

    /** Metering that always reports no employee account. */
    static final class EmptyMetering implements GatewayMetering {
        @Override
        public void putEmployee(String period, String userId, long available) {
        }

        @Override
        public void putEmployee(String period, String userId, long available, long settled, long baseQuota,
                                long floatAllowance) {
        }

        @Override
        public void putPool(String period, GatewayAccountType type, long available) {
        }

        @Override
        public Optional<GatewayAccount> findEmployee(String period, String userId) {
            return Optional.empty();
        }

        @Override
        public Optional<GatewayAccount> findAccount(String period, GatewayAccountType type, String ownerId) {
            return Optional.empty();
        }

        @Override
        public GatewayAttempt reserve(ReserveRequest request) {
            throw new UnsupportedOperationException("must not reserve without account");
        }

        @Override
        public boolean beginSend(String attemptId) {
            return false;
        }

        @Override
        public GatewayAttempt settle(String attemptId, long actualTokens) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GatewayAttempt markPending(String attemptId) {
            return new GatewayAttempt(attemptId, "", 1, "", "", "", "", "", "", 0, 0,
                    GatewayAttemptStatus.PENDING_RECONCILIATION);
        }

        @Override
        public void cancel(String attemptId) {
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
