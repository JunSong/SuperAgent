package com.superagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Conservative R for PR-03 before hard tokenizer evidence (#34 §4): body bytes/4 as input upper bound
 * plus a capped slice of the request's output cap (or a floor).
 *
 * <p>Harness defaults (e.g. dsh {@code max_tokens=256000}) are vendor ceilings, not expected usage —
 * reserving the full ceiling would refuse every turn against a normal employee monthly quota.
 */
final class ReservationEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long OUTPUT_FLOOR = 256;
    /** Practical reserve ceiling for declared max_tokens / max_output_tokens. */
    static final long OUTPUT_RESERVE_CAP = 32_768;

    private ReservationEstimator() {
    }

    static long estimate(byte[] body) {
        long input = body == null ? 0 : Math.max(1, (body.length + 3) / 4);
        long output = OUTPUT_FLOOR;
        if (body != null && body.length > 0) {
            try {
                JsonNode root = JSON.readTree(body);
                long cap = firstPositive(root, "max_tokens", "max_output_tokens");
                if (cap > 0) {
                    output = Math.min(OUTPUT_RESERVE_CAP, Math.max(OUTPUT_FLOOR, cap));
                }
            } catch (Exception ignored) {
                // non-JSON: keep floor
            }
        }
        return input + output;
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
}
