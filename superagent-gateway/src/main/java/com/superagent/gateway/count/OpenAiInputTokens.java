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

/**
 * LiteLLM / OpenAI Responses {@code POST …/responses/input_tokens}.
 */
final class OpenAiInputTokens {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private OpenAiInputTokens() {
    }

    static InputCountResult count(HttpClient http, String openaiRoot, String apiKey, byte[] body)
            throws IOException, InterruptedException {
        JsonNode root = JSON.readTree(body);
        String model = text(root, "model");
        if (model == null || model.isBlank()) {
            throw new IOException("input_tokens requires model");
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", model);
        payload.set("input", toInput(root));
        String url = trimSlash(openaiRoot) + "/responses/input_tokens";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("input_tokens HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        long tokens = readTokens(JSON.readTree(resp.body()));
        if (tokens < 1) {
            throw new IOException("input_tokens missing count: " + truncate(resp.body()));
        }
        return InputCountResult.provider(tokens, url);
    }

    /** Prefer Responses-shaped {@code input}; else flatten chat {@code messages}. */
    static JsonNode toInput(JsonNode root) {
        JsonNode input = root.get("input");
        if (input != null && !input.isNull()) {
            return input.deepCopy();
        }
        JsonNode messages = root.get("messages");
        if (messages instanceof ArrayNode arr && !arr.isEmpty()) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode m : arr) {
                ObjectNode item = JSON.createObjectNode();
                String role = text(m, "role");
                item.put("role", role == null ? "user" : role);
                item.put("content", flattenContent(m.get("content")));
                out.add(item);
            }
            return out;
        }
        String prompt = text(root, "prompt");
        return JSON.getNodeFactory().textNode(prompt == null ? "" : prompt);
    }

    private static long readTokens(JsonNode root) {
        for (String k : new String[]{"input_tokens", "total_tokens", "token_count"}) {
            JsonNode n = root.get(k);
            if (n != null && n.isNumber()) {
                return n.asLong();
            }
        }
        JsonNode usage = root.get("usage");
        if (usage != null) {
            for (String k : new String[]{"input_tokens", "prompt_tokens", "total_tokens"}) {
                JsonNode n = usage.get(k);
                if (n != null && n.isNumber()) {
                    return n.asLong();
                }
            }
        }
        return 0;
    }

    private static String flattenContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
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
                    sb.append(part.path("text").asText(""));
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
