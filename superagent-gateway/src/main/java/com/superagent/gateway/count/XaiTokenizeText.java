package com.superagent.gateway.count;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * LiteLLM-style xAI {@code POST …/tokenize-text}. Renders a coarse chat template then tokenizes.
 * Without measured {@code token_count_framing} this is <strong>not</strong> hard-eligible.
 */
final class XaiTokenizeText {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private XaiTokenizeText() {
    }

    static InputCountResult count(HttpClient http, String openaiRoot, String apiKey, byte[] body)
            throws IOException, InterruptedException {
        JsonNode root = JSON.readTree(body);
        String model = text(root, "model");
        String rendered = renderGrokish(root);
        String url = trimSlash(openaiRoot) + "/tokenize-text";
        ObjectNode payload = JSON.createObjectNode();
        payload.put("text", rendered);
        if (model != null) {
            payload.put("model", model);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("tokenize-text HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        long tokens = readTokenCount(JSON.readTree(resp.body()));
        if (tokens < 1) {
            throw new IOException("tokenize-text missing token count: " + truncate(resp.body()));
        }
        // LiteLLM adds measured token_count_framing; until we calibrate per model, pad conservatively
        // so soft A over-reserves rather than under (still not hardEligible).
        long framing = framingPad(model);
        return InputCountResult.provider(tokens + framing,
                url + " +framing=" + framing + " (not hard-calibrated)");
    }

    /** Conservative pads inspired by LiteLLM cost-map framing for Grok hosts. */
    static long framingPad(String model) {
        if (model == null) {
            return 200;
        }
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("4.3") || m.contains("4-3")) {
            return 220;
        }
        if (m.contains("4.20") || m.contains("4-20") || m.contains("4.7") || m.contains("4-7")) {
            return 200;
        }
        return 180;
    }

    /**
     * Coarse Grok-like render ({@code Human: …\\n\\n}) — LiteLLM uses a fuller template + framing.
     * Good enough for soft A-attempt; not for hardQuotaClis.
     */
    static String renderGrokish(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        JsonNode system = root.get("system");
        if (system != null && system.isTextual() && !system.asText().isBlank()) {
            sb.append("System: ").append(system.asText()).append("\n\n");
        }
        JsonNode messages = root.get("messages");
        if (messages instanceof ArrayNode arr) {
            for (JsonNode m : arr) {
                String role = text(m, "role");
                String content = flattenContent(m.get("content"));
                if (content == null || content.isBlank()) {
                    continue;
                }
                if ("assistant".equalsIgnoreCase(role)) {
                    sb.append("Assistant: ").append(content).append("\n\n");
                } else {
                    sb.append("Human: ").append(content).append("\n\n");
                }
            }
        }
        if (sb.isEmpty()) {
            sb.append("Human: ").append(root.toString()).append("\n\n");
        }
        return sb.toString();
    }

    private static long readTokenCount(JsonNode root) {
        for (String k : new String[]{"token_count", "tokenCount", "count", "input_tokens"}) {
            JsonNode n = root.get(k);
            if (n != null && n.isNumber()) {
                return n.asLong();
            }
        }
        JsonNode tokens = root.get("tokens");
        if (tokens != null && tokens.isArray()) {
            return tokens.size();
        }
        return 0;
    }

    private static String flattenContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.has("text")) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(part.get("text").asText(""));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static String text(JsonNode n, String field) {
        if (n == null) {
            return null;
        }
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
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
