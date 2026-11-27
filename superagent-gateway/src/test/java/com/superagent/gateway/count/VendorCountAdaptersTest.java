package com.superagent.gateway.count;

import com.sun.net.httpserver.HttpServer;
import com.superagent.core.gateway.InputCountSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VendorCountAdaptersTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void openaiInputTokensAndGoogleCountTokens() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses/input_tokens", exchange -> {
            byte[] body = "{\"input_tokens\":111}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1beta/models/gemini-2.0-flash:countTokens", exchange -> {
            byte[] body = "{\"totalTokens\":222}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        InputTokenCounterService svc = new InputTokenCounterService(http);

        byte[] chat = "{\"model\":\"gpt-4.1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        InputCountResult openai = svc.count(new InputCountRequest(
                "codex", "openai", "http://127.0.0.1:" + port + "/v1", "sk", true, chat, false));
        assertThat(openai.source()).isEqualTo(InputCountSource.PROVIDER);
        assertThat(openai.inputTokens()).isEqualTo(111);

        byte[] gem = "{\"model\":\"gemini-2.0-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        InputCountResult google = svc.count(new InputCountRequest(
                "gemini", "other", "http://127.0.0.1:" + port + "/v1beta", "sk", true, gem, false));
        assertThat(google.source()).isEqualTo(InputCountSource.PROVIDER);
        assertThat(google.inputTokens()).isEqualTo(222);
    }
}
