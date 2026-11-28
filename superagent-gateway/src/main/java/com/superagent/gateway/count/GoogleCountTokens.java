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
import java.util.Locale;
import java.util.Set;

/**
 * LiteLLM / Google AI Studio {@code models/{model}:countTokens}.
 * Count URL is always derived from the configured inference base (same host) — never rewritten
 * to a hard-coded Google host (keys must not leave the credential trust boundary).
 */
final class GoogleCountTokens {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    /** Official Google AI Studio hosts only — exact match, no substring. */
    private static final Set<String> GOOGLE_HOSTS = Set.of("generativelanguage.googleapis.com");

    private GoogleCountTokens() {
    }

    static InputCountResult count(HttpClient http, String configuredBase, String apiKey, byte[] body)
            throws IOException, InterruptedException {
        return count(http, configuredBase, apiKey, body, DEFAULT_TIMEOUT);
    }

    static InputCountResult count(HttpClient http, String configuredBase, String apiKey, byte[] body,
                                  Duration timeout)
            throws IOException, InterruptedException {
        JsonNode root = JSON.readTree(body);
        String model = text(root, "model");
        if (model == null || model.isBlank()) {
            model = "gemini-2.0-flash";
        }
        if (model.startsWith("models/")) {
            model = model.substring("models/".length());
        }
        String rootBase = requireCountRoot(configuredBase);
        String url = rootBase + "/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":countTokens";
        ObjectNode payload = buildCountPayload(root);
        Duration t = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(t)
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

    /**
     * Use the credential/inference base verbatim. https required (http only for loopback tests).
     * Official Google hosts must be exact allowlist matches; other https hosts are allowed only
     * when explicitly configured (same trust boundary as inference) — never rewritten to Google.
     */
    static String requireCountRoot(String configuredBase) throws IOException {
        if (configuredBase == null || configuredBase.isBlank()) {
            throw new IOException("Google countTokens requires a configured https base (no silent default host)");
        }
        String rootBase = trimSlash(configuredBase);
        URI uri;
        try {
            uri = URI.create(rootBase);
        } catch (IllegalArgumentException e) {
            throw new IOException("Google countTokens base is not a valid URI: " + rootBase);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("Google countTokens base has no host: " + rootBase);
        }
        String hostLower = host.toLowerCase(Locale.ROOT);
        boolean loopback = "localhost".equals(hostLower) || "127.0.0.1".equals(hostLower) || "::1".equals(host);
        if ("https".equals(scheme)) {
            // ok
        } else if ("http".equals(scheme) && loopback) {
            // local stub tests only
        } else {
            throw new IOException("Google countTokens requires https base, got: " + scheme + "://" + host);
        }
        if (!loopback && !GOOGLE_HOSTS.contains(hostLower)) {
            // Explicit corp / compatible gateway: same host as inference is fine; refuse lookalike
            // Google substrings that are not the real allowlisted host when ops meant Google.
            // Keys stay on the configured host — we never rewrite to generativelanguage.googleapis.com.
            if (hostLower.contains("generativelanguage") || hostLower.contains("googleapis")) {
                throw new IOException("Google countTokens host not on allowlist: " + host
                        + " (allowed: " + GOOGLE_HOSTS + ")");
            }
        }
        return rootBase;
    }

    static ObjectNode buildCountPayload(JsonNode root) {
        ObjectNode payload = JSON.createObjectNode();
        payload.set("contents", toContents(root));
        copyIfPresent(root, payload, "systemInstruction");
        copyIfPresent(root, payload, "system_instruction");
        if (!payload.has("systemInstruction") && !payload.has("system_instruction")) {
            JsonNode system = root.get("system");
            if (system != null && system.isTextual() && !system.asText().isBlank()) {
                ObjectNode si = JSON.createObjectNode();
                ArrayNode parts = JSON.createArrayNode();
                ObjectNode part = JSON.createObjectNode();
                part.put("text", system.asText());
                parts.add(part);
                si.set("parts", parts);
                payload.set("systemInstruction", si);
            } else {
                // OpenAI-shaped: hoist role=system messages
                JsonNode messages = root.get("messages");
                if (messages instanceof ArrayNode arr) {
                    StringBuilder sys = new StringBuilder();
                    for (JsonNode m : arr) {
                        if ("system".equalsIgnoreCase(text(m, "role"))) {
                            String t = flattenContent(m.get("content"));
                            if (t != null && !t.isBlank()) {
                                if (!sys.isEmpty()) {
                                    sys.append('\n');
                                }
                                sys.append(t);
                            }
                        }
                    }
                    if (!sys.isEmpty()) {
                        ObjectNode si = JSON.createObjectNode();
                        ArrayNode parts = JSON.createArrayNode();
                        ObjectNode part = JSON.createObjectNode();
                        part.put("text", sys.toString());
                        parts.add(part);
                        si.set("parts", parts);
                        payload.set("systemInstruction", si);
                    }
                }
            }
        }
        copyIfPresent(root, payload, "tools");
        copyIfPresent(root, payload, "toolConfig");
        copyIfPresent(root, payload, "tool_config");
        return payload;
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
                String role = text(m, "role");
                if ("system".equalsIgnoreCase(role)) {
                    continue; // hoisted into systemInstruction
                }
                ObjectNode c = JSON.createObjectNode();
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

    private static void copyIfPresent(JsonNode root, ObjectNode payload, String field) {
        JsonNode n = root.get(field);
        if (n != null && !n.isNull()) {
            payload.set(field, n.deepCopy());
        }
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
