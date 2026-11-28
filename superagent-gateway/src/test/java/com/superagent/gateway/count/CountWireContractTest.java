package com.superagent.gateway.count;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.superagent.core.gateway.InputCountSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LiteLLM-style wire contracts: local stub returns known response shapes; we assert
 * the outbound count body matches what real endpoints accept (no live keys).
 */
class CountWireContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void anthropicCountTokensWire_stripsGenerationFields_keepsTools_fixtureShape() throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages/count_tokens", exchange -> {
            captured.set(exchange.getRequestBody().readAllBytes());
            // LiteLLM / Anthropic fixture shape
            byte[] body = "{\"input_tokens\":41}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        byte[] chat = """
                {
                  "model":"deepseek-chat",
                  "max_tokens":64,
                  "stream":true,
                  "temperature":0.2,
                  "messages":[{"role":"user","content":"hi"}],
                  "tools":[{"name":"lookup","input_schema":{"type":"object"}}]
                }
                """.getBytes(StandardCharsets.UTF_8);

        InputCountResult r = AnthropicCountTokens.count(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                base, "sk", true, chat, Duration.ofSeconds(2));

        assertThat(r.source()).isEqualTo(InputCountSource.PROVIDER);
        assertThat(r.inputTokens()).isEqualTo(41);
        JsonNode sent = JSON.readTree(captured.get());
        assertThat(sent.has("max_tokens")).isFalse();
        assertThat(sent.has("stream")).isFalse();
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(sent.path("tools")).isNotEmpty();
        assertThat(sent.path("messages")).isNotEmpty();
    }

    @Test
    void openaiInputTokensWire_liftsChatToResponsesParts_fixtureShape() throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses/input_tokens", exchange -> {
            captured.set(exchange.getRequestBody().readAllBytes());
            // LiteLLM / OpenAI fixture shape
            byte[] body = "{\"object\":\"response.input_tokens\",\"input_tokens\":52}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        byte[] chat = """
                {
                  "model":"gpt-4o",
                  "messages":[
                    {"role":"system","content":"You are terse."},
                    {"role":"user","content":[{"type":"text","text":"What is the weather in Paris?"}]},
                    {"role":"assistant","content":[{"type":"text","text":"I will check."}]},
                    {"role":"user","content":"And Rome?"}
                  ],
                  "tools":[{"type":"function","name":"get_weather",
                    "parameters":{"type":"object","properties":{"city":{"type":"string"}}}}]
                }
                """.getBytes(StandardCharsets.UTF_8);

        InputCountResult r = OpenAiInputTokens.count(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                base, "sk", chat, Duration.ofSeconds(2));

        assertThat(r.source()).isEqualTo(InputCountSource.PROVIDER);
        assertThat(r.inputTokens()).isEqualTo(52);
        JsonNode sent = JSON.readTree(captured.get());
        assertThat(sent.path("model").asText()).isEqualTo("gpt-4o");
        assertThat(sent.path("instructions").asText()).isEqualTo("You are terse.");
        assertThat(sent.path("tools")).isNotEmpty();
        JsonNode input = sent.get("input");
        assertThat(input.isArray()).isTrue();
        assertThat(input).hasSize(3); // system hoisted out
        assertThat(input.get(0).path("content").get(0).path("type").asText()).isEqualTo("input_text");
        assertThat(input.get(1).path("content").get(0).path("type").asText()).isEqualTo("output_text");
        assertThat(input.get(2).path("content").get(0).path("type").asText()).isEqualTo("input_text");
        // No chat-style type:text parts on the wire
        for (JsonNode turn : input) {
            for (JsonNode part : turn.path("content")) {
                assertThat(part.path("type").asText()).isNotEqualTo("text");
            }
        }
    }

    @Test
    void openaiNativeResponsesInput_passthroughPreservesParts() throws Exception {
        JsonNode payload = OpenAiInputTokens.buildCountPayload(JSON.readTree("""
                {
                  "model":"gpt-4o",
                  "input":[{
                    "role":"user",
                    "content":[
                      {"type":"input_text","text":"see image"},
                      {"type":"input_image","image_url":"data:image/png;base64,xx"}
                    ]
                  }],
                  "instructions":"be brief"
                }
                """));
        assertThat(payload.path("instructions").asText()).isEqualTo("be brief");
        JsonNode parts = payload.path("input").get(0).path("content");
        assertThat(parts.get(0).path("type").asText()).isEqualTo("input_text");
        assertThat(parts.get(1).path("type").asText()).isEqualTo("input_image");
    }

    @Test
    void googleCountTokensWire_includesSystemTools_fixtureShape() throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/gemini-2.0-flash:countTokens", exchange -> {
            captured.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"totalTokens\":222}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
        byte[] gem = """
                {
                  "model":"gemini-2.0-flash",
                  "systemInstruction":{"parts":[{"text":"be brief"}]},
                  "contents":[{"role":"user","parts":[{"text":"hi"}]}],
                  "tools":[{"functionDeclarations":[{"name":"x"}]}],
                  "toolConfig":{"functionCallingConfig":{"mode":"AUTO"}}
                }
                """.getBytes(StandardCharsets.UTF_8);

        InputCountResult r = GoogleCountTokens.count(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                base, "sk", gem, Duration.ofSeconds(2));

        assertThat(r.source()).isEqualTo(InputCountSource.PROVIDER);
        assertThat(r.inputTokens()).isEqualTo(222);
        JsonNode sent = JSON.readTree(captured.get());
        assertThat(sent.has("systemInstruction")).isTrue();
        assertThat(sent.has("tools")).isTrue();
        assertThat(sent.has("toolConfig")).isTrue();
        assertThat(sent.path("contents")).isNotEmpty();
    }
}
