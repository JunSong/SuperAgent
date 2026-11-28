package com.superagent.gateway.count;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LiteLLM / Anthropic {@code POST …/v1/messages/count_tokens}.
 */
final class AnthropicCountTokens {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private AnthropicCountTokens() {
    }

    static InputCountResult count(HttpClient http, String anthropicBase, String apiKey, boolean bearer, byte[] body)
            throws IOException, InterruptedException {
        return count(http, anthropicBase, apiKey, bearer, body, TIMEOUT);
    }

    static InputCountResult count(HttpClient http, String anthropicBase, String apiKey, boolean bearer, byte[] body,
                                  Duration timeout)
            throws IOException, InterruptedException {
        String url = trimSlash(anthropicBase) + "/v1/messages/count_tokens";
        byte[] countBody = stripForCount(body);
        Duration t = timeout == null || timeout.isNegative() || timeout.isZero() ? TIMEOUT : timeout;
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(t)
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofByteArray(countBody));
        // Match inference auth: bearer OR x-api-key, not both.
        if (bearer) {
            req.header("Authorization", "Bearer " + apiKey);
        } else {
            req.header("x-api-key", apiKey);
        }
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("count_tokens HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        JsonNode root = JSON.readTree(resp.body());
        JsonNode n = root.get("input_tokens");
        if (n == null || !n.isNumber() || n.asLong() < 1) {
            throw new IOException("count_tokens missing input_tokens: " + truncate(resp.body()));
        }
        return InputCountResult.provider(n.asLong(), url);
    }

    /** Drop generation-only fields vendors reject on count routes. */
    static byte[] stripForCount(byte[] body) throws IOException {
        JsonNode root = JSON.readTree(body);
        if (!(root instanceof ObjectNode obj)) {
            throw new IOException("count_tokens body must be a JSON object");
        }
        ObjectNode copy = obj.deepCopy();
        for (String f : new String[]{
                "stream", "max_tokens", "max_output_tokens", "max_completion_tokens",
                "temperature", "top_p", "top_k", "stop_sequences"
        }) {
            copy.remove(f);
        }
        return JSON.writeValueAsBytes(copy);
    }

    private static String trimSlash(String base) {
        String b = base == null ? "" : base.strip();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}
