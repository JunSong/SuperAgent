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
 * Chat {@code messages} are lifted into Responses {@code input} with
 * {@code input_text}/{@code output_text} parts — plain {@code type:text} is rejected by the real endpoint.
 */
final class OpenAiInputTokens {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private OpenAiInputTokens() {
    }

    static InputCountResult count(HttpClient http, String openaiRoot, String apiKey, byte[] body)
            throws IOException, InterruptedException {
        return count(http, openaiRoot, apiKey, body, TIMEOUT);
    }

    static InputCountResult count(HttpClient http, String openaiRoot, String apiKey, byte[] body, Duration timeout)
            throws IOException, InterruptedException {
        JsonNode root = JSON.readTree(body);
        ObjectNode payload = buildCountPayload(root);
        String url = trimSlash(openaiRoot) + "/responses/input_tokens";
        Duration t = timeout == null || timeout.isNegative() || timeout.isZero() ? TIMEOUT : timeout;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(t)
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

    /** LiteLLM-aligned count body: model + input (+ instructions/tools when present). */
    static ObjectNode buildCountPayload(JsonNode root) throws IOException {
        String model = text(root, "model");
        if (model == null || model.isBlank()) {
            throw new IOException("input_tokens requires model");
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", model);
        payload.set("input", toInput(root));
        copyIfPresent(root, payload, "instructions");
        copyIfPresent(root, payload, "tools");
        if (!payload.has("instructions")) {
            String system = hoistSystem(root);
            if (system != null && !system.isBlank()) {
                payload.put("instructions", system);
            }
        }
        return payload;
    }

    /**
     * Prefer Responses-shaped {@code input}; else lift chat {@code messages} into
     * role + content-part arrays ({@code input_text}/{@code output_text}).
     */
    static JsonNode toInput(JsonNode root) {
        JsonNode input = root.get("input");
        if (input != null && !input.isNull()) {
            return normalizeResponsesInput(input.deepCopy());
        }
        JsonNode messages = root.get("messages");
        if (messages instanceof ArrayNode arr && !arr.isEmpty()) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode m : arr) {
                String role = text(m, "role");
                if ("system".equalsIgnoreCase(role)) {
                    continue; // hoisted to instructions
                }
                ObjectNode item = JSON.createObjectNode();
                item.put("role", role == null ? "user" : role);
                item.set("content", toResponsesContent(role, m.get("content")));
                out.add(item);
            }
            return out;
        }
        String prompt = text(root, "prompt");
        return JSON.getNodeFactory().textNode(prompt == null ? "" : prompt);
    }

    /** Ensure array content parts use Responses types, not chat {@code text}. */
    static JsonNode normalizeResponsesInput(JsonNode input) {
        if (!(input instanceof ArrayNode arr)) {
            return input;
        }
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode item : arr) {
            if (!(item instanceof ObjectNode obj)) {
                out.add(item.deepCopy());
                continue;
            }
            ObjectNode copy = obj.deepCopy();
            String role = text(copy, "role");
            JsonNode content = copy.get("content");
            if (content != null) {
                copy.set("content", toResponsesContent(role, content));
            }
            out.add(copy);
        }
        return out;
    }

    static ArrayNode toResponsesContent(String role, JsonNode content) {
        ArrayNode parts = JSON.createArrayNode();
        boolean assistant = "assistant".equalsIgnoreCase(role);
        if (content == null || content.isNull()) {
            parts.add(textPart(assistant, ""));
            return parts;
        }
        if (content.isTextual()) {
            parts.add(textPart(assistant, content.asText()));
            return parts;
        }
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (part == null || part.isNull()) {
                    continue;
                }
                if (part.isTextual()) {
                    parts.add(textPart(assistant, part.asText()));
                    continue;
                }
                String type = text(part, "type");
                if (type == null || type.isBlank() || "text".equals(type)) {
                    ObjectNode p = JSON.createObjectNode();
                    p.put("type", assistant ? "output_text" : "input_text");
                    p.put("text", part.path("text").asText(part.toString()));
                    parts.add(p);
                } else if ("input_text".equals(type) || "output_text".equals(type)
                        || "input_image".equals(type) || "input_file".equals(type)
                        || "refusal".equals(type)) {
                    parts.add(part.deepCopy());
                } else {
                    // Unknown part: keep as-is so we don't invent types the API rejects.
                    parts.add(part.deepCopy());
                }
            }
            if (parts.isEmpty()) {
                parts.add(textPart(assistant, ""));
            }
            return parts;
        }
        parts.add(textPart(assistant, content.toString()));
        return parts;
    }

    private static ObjectNode textPart(boolean assistant, String text) {
        ObjectNode p = JSON.createObjectNode();
        p.put("type", assistant ? "output_text" : "input_text");
        p.put("text", text == null ? "" : text);
        return p;
    }

    private static String hoistSystem(JsonNode root) {
        JsonNode system = root.get("system");
        if (system != null && system.isTextual() && !system.asText().isBlank()) {
            return system.asText();
        }
        JsonNode messages = root.get("messages");
        if (!(messages instanceof ArrayNode arr)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : arr) {
            if (!"system".equalsIgnoreCase(text(m, "role"))) {
                continue;
            }
            String t = flattenTextOnly(m.get("content"));
            if (t == null || t.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String flattenTextOnly(JsonNode content) {
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
                    sb.append(part.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static void copyIfPresent(JsonNode root, ObjectNode payload, String field) {
        JsonNode n = root.get(field);
        if (n != null && !n.isNull()) {
            payload.set(field, n.deepCopy());
        }
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
