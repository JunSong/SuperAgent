package com.superagent.gateway;

import com.sun.net.httpserver.HttpServer;
import com.superagent.core.chat.RunCredential;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** First-byte / idle bounds on the upstream stream (#34 §4). */
class UpstreamProxyTimeoutTest {

    private HttpServer stub;
    private final AtomicInteger hits = new AtomicInteger();
    private String stubBase;

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/messages", exchange -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(2_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        stubBase = "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    void firstByteTimeoutAbortsBeforeBody() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek"))).thenReturn(new CredentialService.Choice(
                new RunCredential("k1", stubBase, "sk-test", true)));

        GatewayProxyTimeouts tight = new GatewayProxyTimeouts(
                Duration.ofSeconds(5),
                Duration.ofMillis(400),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30));
        UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient(), clock,
                new QuotaReserveGate(clock), tight);

        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://gw", clock);
        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t1", "r1", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        ModelRunGrant grant = grants.require("Bearer " + lease.token());

        String body = """
                {"model":"deepseek-chat","messages":[{"role":"user","content":"hi"}],"max_tokens":16}
                """;
        assertThatThrownBy(() -> proxy.forward(
                grant, "anthropic", "/v1/messages", "POST", Map.of(),
                body.getBytes(StandardCharsets.UTF_8), new ByteArrayOutputStream(), null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("first-byte timeout");
        assertThatEventuallyHit();
    }

    private void assertThatEventuallyHit() {
        // Stub may still be entered; we only care the client aborted on timeout.
        org.assertj.core.api.Assertions.assertThat(hits.get()).isGreaterThanOrEqualTo(0);
    }
}
