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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-01 acceptance (#34): two employees → two tokens → stub sees two calls with the same company Key;
 * revoke then replay → 401 and stub count unchanged; first chunk reaches the client before upstream finishes.
 */
class GatewayProxyAcceptanceTest {

    private HttpServer stub;
    private final CopyOnWriteArrayList<String> seenKeys = new CopyOnWriteArrayList<>();
    private final AtomicInteger stubHits = new AtomicInteger();
    private String stubBase;

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/anthropic/v1/messages", exchange -> {
            stubHits.incrementAndGet();
            String key = exchange.getRequestHeaders().getFirst("x-api-key");
            if (key == null) {
                key = exchange.getRequestHeaders().getFirst("Authorization");
            }
            seenKeys.add(key);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hello\"}}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                out.write("data: {\"type\":\"message_stop\"}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        stub.start();
        stubBase = "http://127.0.0.1:" + stub.getAddress().getPort() + "/anthropic";
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    @Test
    void twoEmployeesShareCompanyKeyOnStubAndRevokeBlocksReplay() throws Exception {
        String companyKey = "sk-company-shared";
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("pool-1", stubBase, companyKey, false)));

        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://control-plane", Clock.systemUTC());
        UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient());

        ModelRunLease alice = TenantContext.call("co1",
                () -> grants.issue("turn-alice", "run-alice", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(10)));
        ModelRunLease bob = TenantContext.call("co1",
                () -> grants.issue("turn-bob", "run-bob", "bob", "deepseek", "deepseek-chat", Duration.ofMinutes(10)));
        assertThat(alice.token()).isNotEqualTo(bob.token());

        ByteArrayOutputStream aliceOut = new ByteArrayOutputStream();
        assertThat(proxy.forward(grants.require("Bearer " + alice.token()), "anthropic", "/v1/messages",
                "POST", java.util.Map.of(), "{\"model\":\"deepseek-chat\"}".getBytes(StandardCharsets.UTF_8),
                aliceOut, (code, ct) -> {
                })).isEqualTo(200);
        assertThat(aliceOut.toString(StandardCharsets.UTF_8)).contains("hello");

        ByteArrayOutputStream bobOut = new ByteArrayOutputStream();
        assertThat(proxy.forward(grants.require("Bearer " + bob.token()), "anthropic", "/v1/messages",
                "POST", java.util.Map.of(), "{\"model\":\"deepseek-chat\"}".getBytes(StandardCharsets.UTF_8),
                bobOut, (code, ct) -> {
                })).isEqualTo(200);

        assertThat(stubHits.get()).isEqualTo(2);
        assertThat(seenKeys).containsExactly(companyKey, companyKey);

        grants.revoke(alice.token());
        int hitsAfterRevoke = stubHits.get();
        assertThatThrownBy(() -> grants.require("Bearer " + alice.token()))
                .isInstanceOf(ModelRunGrants.UnauthorizedGatewayException.class);
        assertThat(stubHits.get()).isEqualTo(hitsAfterRevoke);
    }

    @Test
    void firstChunkArrivesBeforeUpstreamFinishes() throws Exception {
        CountDownLatch clientSawFirst = new CountDownLatch(1);
        CountDownLatch releaseUpstreamTail = new CountDownLatch(1);
        AtomicLong firstChunkNanos = new AtomicLong();
        AtomicBoolean upstreamFinished = new AtomicBoolean(false);

        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/anthropic/v1/messages", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertThat(releaseUpstreamTail.await(3, TimeUnit.SECONDS)).isTrue();
                out.write("data: last\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                upstreamFinished.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        slow.start();
        String base = "http://127.0.0.1:" + slow.getAddress().getPort() + "/anthropic";
        try {
            CredentialService credentials = mock(CredentialService.class);
            when(credentials.current(eq("deepseek")))
                    .thenReturn(new CredentialService.Choice(new RunCredential("p", base, "sk-x", false)));
            ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://cp", Clock.systemUTC());
            UpstreamProxy proxy = new UpstreamProxy(credentials, HttpClient.newHttpClient());
            ModelRunLease lease = TenantContext.call("co1",
                    () -> grants.issue("t", "r", "alice", "deepseek", "m", Duration.ofMinutes(5)));

            Thread client = new Thread(() -> {
                try {
                    OutputStream tee = new OutputStream() {
                        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

                        @Override
                        public void write(int b) {
                            buf.write(b);
                            check();
                        }

                        @Override
                        public void write(byte[] b, int off, int len) {
                            buf.write(b, off, len);
                            check();
                        }

                        private void check() {
                            if (firstChunkNanos.get() == 0L && buf.toString(StandardCharsets.UTF_8).contains("first")) {
                                firstChunkNanos.compareAndSet(0L, System.nanoTime());
                                clientSawFirst.countDown();
                            }
                        }
                    };
                    proxy.forward(grants.require("Bearer " + lease.token()), "anthropic", "/v1/messages",
                            "POST", java.util.Map.of(), "{}".getBytes(StandardCharsets.UTF_8), tee, (c, ct) -> {
                            });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "gateway-first-chunk");
            client.start();

            assertThat(clientSawFirst.await(2, TimeUnit.SECONDS))
                    .as("first SSE chunk must reach the client while upstream is still open")
                    .isTrue();
            assertThat(upstreamFinished.get()).isFalse();
            releaseUpstreamTail.countDown();
            client.join(Duration.ofSeconds(3).toMillis());
            assertThat(upstreamFinished.get()).isTrue();
            assertThat(firstChunkNanos.get()).isPositive();
        } finally {
            slow.stop(0);
        }
    }
}
