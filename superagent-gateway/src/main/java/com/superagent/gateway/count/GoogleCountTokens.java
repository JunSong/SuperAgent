package com.superagent.gateway.count;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * LiteLLM / Google AI Studio {@code models/{model}:countTokens}.
 */
final class GoogleCountTokens {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String DEFAULT_ROOT = "https://generativelanguage.googleapis.com/v1beta";

    private GoogleCountTokens() {
    }

    static InputCountResult count(HttpClient http, String configuredBase, String apiKey, byte[] body)
            throws IOException, InterruptedException {
        JsonNode root = JSON.readTree(body);
        String model = text(root, "model");
        if (model == null || model.isBlank()) {
            model = "gemini-2.0-flash";
        }
        // Strip provider prefixes gateways sometimes add.
        if (model.startsWith("models/")) {
            model = model.substring("models/".length());
        }
        String rootBase = (configuredBase == null || configuredBase.isBlank()) ? DEFAULT_ROOT : trimSlash(configuredBase);
        if (!rootBase.contains("generativelanguage") && !rootBase.contains("/v1beta")) {
            // Credential may hold an unrelated OpenAI-shaped base; use Google default.
            rootBase = DEFAULT_ROOT;
        }
        String url = rootBase + "/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":countTokens";
        ObjectNode payload = JSON.createObjectNode();
        payload.set("contents", toContents(root));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("countTokens HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        JsonNode parsed = JSON.readTree(resp.body());
        long tokens = 0;
        JsonNode total = parsed.get("totalTokens");
        if (total != null && total.isNumber()) {
            tokens = total.asLong();
        }
        if (tokens < 1) {
            throw new IOException("countTokens missing totalTokens: " + truncate(resp.body()));
        }
        return InputCountResult.provider(tokens, url);
    }

    static ArrayNode toContents(JsonNode root) {
        ArrayNode contents = JSON.createArrayNode();
        JsonNode geminiContents = root.get("contents");
        if (geminiContents instanceof ArrayNode arr && !arr.isEmpty()) {
            return arr.deepCopy();
        }
        JsonNode messages = root.get("messages");
        if (messages instanceof ArrayNode arr) {
            for (JsonNode m : arr) {
                ObjectNode c = JSON.createObjectNode();
                String role = text(m, "role");
                c.put("role", "assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role) ? "model" : "user");
                ArrayNode parts = JSON.createArrayNode();
                ObjectNode part = JSON.createObjectNode();
                part.put("text", flattenContent(m.get("content")));
                parts.add(part);
                c.set("parts", parts);
                contents.add(c);
            }
        }
        if (contents.isEmpty()) {
            ObjectNode c = JSON.createObjectNode();
            c.put("role", "user");
            ArrayNode parts = JSON.createArrayNode();
            ObjectNode part = JSON.createObjectNode();
            part.put("text", root.toString());
            parts.add(part);
            c.set("parts", parts);
            contents.add(c);
        }
        return contents;
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
