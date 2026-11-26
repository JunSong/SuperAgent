package com.superagent.gateway;

import com.sun.net.httpserver.HttpServer;
import com.superagent.core.chat.RunCredential;
import com.superagent.core.gateway.ModelRunLease;
import com.superagent.core.tenant.TenantContext;
import com.superagent.platform.provider.CredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** First-byte / idle / whole-request bounds on the upstream stream (#34 §4 / review I02). */
class UpstreamProxyTimeoutTest {

    private HttpServer stub;
    private final AtomicInteger hits = new AtomicInteger();

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop(0);
            stub = null;
        }
    }

    @Test
    void firstByteTimeoutAbortsBeforeBody() throws Exception {
        String stubBase = startStub((exchange, out) -> {
            Thread.sleep(2_500);
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            out.write(body);
        });

        GatewayProxyTimeouts tight = new GatewayProxyTimeouts(
                Duration.ofSeconds(5),
                Duration.ofMillis(400),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30));
        UpstreamProxy proxy = proxy(stubBase, tight);

        assertThatThrownBy(() -> forward(proxy))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("first-byte timeout");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void reviewWholeRequestDeadlineMustCoverStreamingBody() throws Exception {
        // Headers immediately; trickle SSE every 100ms for > request timeout while staying under idle.
        String stubBase = startStub((exchange, out) -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            long end = System.nanoTime() + Duration.ofMillis(1_500).toNanos();
            while (System.nanoTime() < end) {
                out.write("data: ping\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(100);
            }
        });

        GatewayProxyTimeouts tight = new GatewayProxyTimeouts(
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                Duration.ofMillis(400));
        UpstreamProxy proxy = proxy(stubBase, tight);

        assertThatThrownBy(() -> forward(proxy))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("request timeout");
        assertThat(hits.get()).isEqualTo(1);
    }

    private String startStub(StubHandler handler) throws IOException {
        hits.set(0);
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/messages", exchange -> {
            hits.incrementAndGet();
            try (OutputStream out = exchange.getResponseBody()) {
                handler.handle(exchange, out);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // client closed mid-stream
            } finally {
                exchange.close();
            }
        });
        stub.start();
        return "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    private static UpstreamProxy proxy(String stubBase, GatewayProxyTimeouts timeouts) {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek"))).thenReturn(new CredentialService.Choice(
                new RunCredential("k1", stubBase, "sk-test", true)));
        return new UpstreamProxy(credentials, HttpClient.newHttpClient(), clock,
                new QuotaReserveGate(clock), timeouts);
    }

    private static void forward(UpstreamProxy proxy) throws IOException, InterruptedException {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://gw", clock);
        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t1", "r1", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        ModelRunGrant grant = grants.require("Bearer " + lease.token());
        String body = """
                {"model":"deepseek-chat","messages":[{"role":"user","content":"hi"}],"max_tokens":16}
                """;
        proxy.forward(grant, "anthropic", "/v1/messages", "POST", Map.of(),
                body.getBytes(StandardCharsets.UTF_8), new ByteArrayOutputStream(), null);
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange, OutputStream out) throws Exception;
    }
}
