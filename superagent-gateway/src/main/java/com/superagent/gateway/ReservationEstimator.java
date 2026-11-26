package com.superagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Conservative R before hard tokenizer evidence (#34 §4): body bytes/4 as input upper bound
 * plus a capped slice of the request's output cap (or a floor when the request omits one).
 *
 * <p>Rewrites only the protocol-appropriate output field so the upstream cannot emit more than
 * reserved — never raises a caller-declared cap, and never injects foreign fields.
 */
final class ReservationEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Reserved when the request omits every output cap (hard ceiling for uncapped calls). */
    private static final long OUTPUT_FLOOR = 256;
    /** Practical reserve ceiling for declared output caps. */
    static final long OUTPUT_RESERVE_CAP = 32_768;
    private static final String[] OUTPUT_FIELDS = {"max_tokens", "max_output_tokens", "max_completion_tokens"};

    record Prepared(long reservedTokens, byte[] body, long outputCap) {
    }

    private ReservationEstimator() {
    }

    static long estimate(byte[] body) {
        return prepare(body, ModelRunGrants.PROTOCOL_OPENAI, "/v1/chat/completions").reservedTokens();
    }

    static Prepared prepare(byte[] body) {
        return prepare(body, ModelRunGrants.PROTOCOL_OPENAI, "/v1/chat/completions");
    }

    /**
     * @param protocol {@link ModelRunGrants#PROTOCOL_ANTHROPIC} / {@link ModelRunGrants#PROTOCOL_OPENAI}
     * @param path     normalized gateway suffix (e.g. {@code /v1/messages}, {@code /v1/responses})
     */
    static Prepared prepare(byte[] body, String protocol, String path) {
        long input = body == null ? 0 : Math.max(1, (body.length + 3) / 4);
        long output = OUTPUT_FLOOR;
        byte[] outbound = body == null ? new byte[0] : body;
        if (body != null && body.length > 0) {
            try {
                JsonNode root = JSON.readTree(body);
                long declared = firstPositive(root, OUTPUT_FIELDS);
                if (declared > 0) {
                    // Never raise a caller-declared cap (P2); only clamp down to the reserve ceiling.
                    output = Math.min(OUTPUT_RESERVE_CAP, declared);
                }
                if (root instanceof ObjectNode obj) {
                    boolean changed = applyOutputCap(obj, output, protocol, path);
                    if (changed) {
                        outbound = JSON.writeValueAsBytes(obj);
                    }
                }
            } catch (Exception ignored) {
                // non-JSON: keep floor, original bytes
            }
        }
        return new Prepared(input + output, outbound, output);
    }

    /**
     * Clamp only fields the request already carries. If none, inject exactly one field chosen by
     * protocol/path (Anthropic → max_tokens, Responses → max_output_tokens, Chat → max_tokens
     * unless max_completion_tokens was the natural choice).
     */
    private static boolean applyOutputCap(ObjectNode obj, long output, String protocol, String path) {
        boolean anyPresent = false;
        boolean changed = false;
        for (String f : OUTPUT_FIELDS) {
            if (!obj.has(f)) {
                continue;
            }
            anyPresent = true;
            JsonNode n = obj.get(f);
            if (!n.isNumber() || n.asLong() != output) {
                obj.put(f, output);
                changed = true;
            }
        }
        if (!anyPresent) {
            String field = preferredOutputField(protocol, path, obj);
            obj.put(field, output);
            changed = true;
        }
        return changed;
    }

    static String preferredOutputField(String protocol, String path, JsonNode root) {
        String p = protocol == null ? "" : protocol.toLowerCase(Locale.ROOT);
        String suffix = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (ModelRunGrants.PROTOCOL_ANTHROPIC.equals(p)) {
            return "max_tokens";
        }
        if (suffix.contains("responses")) {
            return "max_output_tokens";
        }
        // OpenAI Chat Completions: prefer max_completion_tokens only when already present.
        if (root != null && root.has("max_completion_tokens")) {
            return "max_completion_tokens";
        }
        return "max_tokens";
    }

    private static long firstPositive(JsonNode root, String... fields) {
        for (String f : fields) {
            JsonNode n = root.get(f);
            if (n != null && n.isNumber() && n.asLong() > 0) {
                return n.asLong();
            }
        }
        return 0;
    }

    static String requestModel(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(body).get("model");
            return n != null && n.isTextual() ? n.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Package-visible for tests that assert clamp text. */
    static String asUtf8(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}
