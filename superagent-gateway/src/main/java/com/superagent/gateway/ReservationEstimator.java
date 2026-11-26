package com.superagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * Conservative R before hard tokenizer evidence (#34 §4): body bytes/4 as input upper bound
 * plus a capped slice of the request's output cap (or a floor).
 *
 * <p>Rewrites outbound output fields ({@code max_tokens} / {@code max_output_tokens} /
 * {@code max_completion_tokens}) so the upstream cannot emit more than what was reserved.
 * When the request omits every output cap, injects them at the reserved floor.
 */
final class ReservationEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long OUTPUT_FLOOR = 256;
    /** Practical reserve ceiling for declared max_tokens / max_output_tokens / max_completion_tokens. */
    static final long OUTPUT_RESERVE_CAP = 32_768;
    private static final String[] OUTPUT_FIELDS = {"max_tokens", "max_output_tokens", "max_completion_tokens"};

    record Prepared(long reservedTokens, byte[] body, long outputCap) {
    }

    private ReservationEstimator() {
    }

    static long estimate(byte[] body) {
        return prepare(body).reservedTokens();
    }

    /**
     * Computes R and returns a body whose output fields are clamped (or injected) to the reserved output slice.
     */
    static Prepared prepare(byte[] body) {
        long input = body == null ? 0 : Math.max(1, (body.length + 3) / 4);
        long output = OUTPUT_FLOOR;
        byte[] outbound = body == null ? new byte[0] : body;
        if (body != null && body.length > 0) {
            try {
                JsonNode root = JSON.readTree(body);
                long declared = firstPositive(root, OUTPUT_FIELDS);
                if (declared > 0) {
                    output = Math.min(OUTPUT_RESERVE_CAP, Math.max(OUTPUT_FLOOR, declared));
                }
                if (root instanceof ObjectNode obj) {
                    boolean changed = applyOutputCap(obj, output);
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

    /** Clamp existing output fields; if none exist, inject all three so no protocol path is uncapped. */
    private static boolean applyOutputCap(ObjectNode obj, long output) {
        boolean anyPresent = false;
        boolean changed = false;
        for (String f : OUTPUT_FIELDS) {
            if (obj.has(f)) {
                anyPresent = true;
                JsonNode n = obj.get(f);
                if (!n.isNumber() || n.asLong() != output) {
                    obj.put(f, output);
                    changed = true;
                }
            }
        }
        if (!anyPresent) {
            for (String f : OUTPUT_FIELDS) {
                obj.put(f, output);
            }
            changed = true;
        }
        return changed;
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
