package com.superagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * Conservative R before hard tokenizer evidence (#34 §4): body bytes/4 as input upper bound
 * plus a capped slice of the request's output cap (or a floor).
 *
 * <p>Also rewrites {@code max_tokens}/{@code max_output_tokens} on the outbound body so the
 * upstream cannot emit more than what was reserved (R01 / C2).
 */
final class ReservationEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long OUTPUT_FLOOR = 256;
    /** Practical reserve ceiling for declared max_tokens / max_output_tokens. */
    static final long OUTPUT_RESERVE_CAP = 32_768;

    record Prepared(long reservedTokens, byte[] body, long outputCap) {
    }

    private ReservationEstimator() {
    }

    static long estimate(byte[] body) {
        return prepare(body).reservedTokens();
    }

    /**
     * Computes R and returns a body whose output fields are clamped to the reserved output slice.
     */
    static Prepared prepare(byte[] body) {
        long input = body == null ? 0 : Math.max(1, (body.length + 3) / 4);
        long output = OUTPUT_FLOOR;
        byte[] outbound = body == null ? new byte[0] : body;
        if (body != null && body.length > 0) {
            try {
                JsonNode root = JSON.readTree(body);
                long declared = firstPositive(root, "max_tokens", "max_output_tokens");
                if (declared > 0) {
                    output = Math.min(OUTPUT_RESERVE_CAP, Math.max(OUTPUT_FLOOR, declared));
                }
                if (root instanceof ObjectNode obj && declared > output) {
                    if (obj.has("max_tokens")) {
                        obj.put("max_tokens", output);
                    }
                    if (obj.has("max_output_tokens")) {
                        obj.put("max_output_tokens", output);
                    }
                    outbound = JSON.writeValueAsBytes(obj);
                    // Input estimate stays on the original body size (conservative for clamp shrink).
                }
            } catch (Exception ignored) {
                // non-JSON: keep floor, original bytes
            }
        }
        return new Prepared(input + output, outbound, output);
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
