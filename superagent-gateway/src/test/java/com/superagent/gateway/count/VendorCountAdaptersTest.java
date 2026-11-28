package com.superagent.gateway.count;

import com.sun.net.httpserver.HttpServer;
import com.superagent.core.gateway.InputCountSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

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
        AtomicInteger googleHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses/input_tokens", exchange -> {
            byte[] body = "{\"input_tokens\":111}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1beta/models/gemini-2.0-flash:countTokens", exchange -> {
            googleHits.incrementAndGet();
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
        // Same-host loopback base only — never rewritten to Google public API.
        InputCountResult google = svc.count(new InputCountRequest(
                "gemini", "other", "http://127.0.0.1:" + port + "/v1beta", "sk", true, gem, false));
        assertThat(google.source()).isEqualTo(InputCountSource.PROVIDER);
        assertThat(google.inputTokens()).isEqualTo(222);
        assertThat(googleHits.get()).isEqualTo(1);

        // Blank / lookalike bases must not rewrite to Google public API (no outbound hit).
        int before = googleHits.get();
        InputCountResult blank = svc.count(new InputCountRequest(
                "gemini", "other", "", "sk-corp", true, gem, false));
        assertThat(blank.source()).isEqualTo(InputCountSource.HEURISTIC);
        InputCountResult lookalike = svc.count(new InputCountRequest(
                "gemini", "other", "https://generativelanguage.evil.example/v1beta", "sk-corp", true, gem, false));
        assertThat(lookalike.source()).isEqualTo(InputCountSource.HEURISTIC);
        assertThat(googleHits.get()).isEqualTo(before);
    }

    @Test
    void grokTokenizeTextUsesTokenIdsLengthAsLocalEstimate() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tokenize-text", exchange -> {
            hits.incrementAndGet();
            // Live xAI shape (I3 2026-11-28)
            byte[] body = "{\"token_ids\":[{\"token_id\":1},{\"token_id\":2},{\"token_id\":3}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        InputTokenCounterService svc = new InputTokenCounterService(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        byte[] body = "{\"model\":\"grok-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        InputCountResult r = svc.count(new InputCountRequest(
                "grok", "openai", "http://127.0.0.1:" + port + "/v1", "sk", true, body, false));
        assertThat(hits.get()).isEqualTo(1);
        assertThat(r.source()).isEqualTo(InputCountSource.LOCAL_ESTIMATE);
        // 3 token_ids + framing pad (180 for generic grok-4)
        assertThat(r.inputTokens()).isEqualTo(3 + 180);
        assertThat(r.detail()).contains("uncalibrated");
    }
}
