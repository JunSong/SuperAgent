package com.superagent.gateway.count;

import com.sun.net.httpserver.HttpServer;
import com.superagent.core.gateway.InputCountSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InputTokenCounterServiceTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/anthropic/v1/messages/count_tokens", exchange -> {
            byte[] body = "{\"input_tokens\":321}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/anthropic/v1/messages/count_tokens-fail", exchange -> {
            byte[] body = "{\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/anthropic";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void softPathUsesProviderCountForDeepseek() {
        InputTokenCounterService svc = new InputTokenCounterService(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        byte[] body = "{\"model\":\"deepseek-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":16}"
                .getBytes(StandardCharsets.UTF_8);
        InputCountResult r = svc.count(new InputCountRequest(
                "deepseek", "anthropic", base, "sk-test", true, body, false));
        assertThat(r.source()).isEqualTo(InputCountSource.PROVIDER);
        assertThat(r.inputTokens()).isEqualTo(321);
    }

    @Test
    void softPathFallsBackToHeuristicWhenCountFails() {
        InputTokenCounterService svc = new InputTokenCounterService(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        byte[] body = "{\"model\":\"x\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        InputCountResult r = svc.count(new InputCountRequest(
                "deepseek", "anthropic", base + "-missing", "sk-test", true, body, false));
        assertThat(r.source()).isEqualTo(InputCountSource.HEURISTIC);
        assertThat(r.inputTokens()).isEqualTo(Math.max(1, (body.length + 3) / 4));
    }

    @Test
    void hardPreferredFallsBackToHeuristicWhenCountFails() {
        InputTokenCounterService svc = new InputTokenCounterService(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        byte[] body = "{\"model\":\"x\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        InputCountResult r = svc.count(new InputCountRequest(
                "deepseek", "anthropic", base + "-missing", "sk-test", true, body, true));
        assertThat(r.source()).isEqualTo(InputCountSource.HEURISTIC);
        assertThat(r.inputTokens()).isEqualTo(Math.max(1, (body.length + 3) / 4));
    }

    @Test
    void minimaxLocalTokenizerStandIn() {
        InputTokenCounterService svc = new InputTokenCounterService(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        byte[] body = "{\"model\":\"MiniMax-M2\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        InputCountResult r = svc.count(new InputCountRequest(
                "minimax", "openai", "https://api.minimax.chat/v1", "sk-test", true, body, false));
        assertThat(r.source()).isEqualTo(InputCountSource.LOCAL_ESTIMATE);
        assertThat(r.detail()).contains("not bundled");
    }
}
