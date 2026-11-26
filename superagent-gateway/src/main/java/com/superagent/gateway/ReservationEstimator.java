package com.superagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.error.ForbiddenException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Conservative R before hard tokenizer evidence (#34 §4): body bytes/4 as input upper bound
 * plus a capped slice of the request's protocol-bound output cap (or a floor when omitted).
 *
 * <p>Rewrites only the single protocol-appropriate output field so the upstream cannot emit more
 * than reserved — never raises a caller-declared cap, and never treats foreign protocol fields as
 * the declared cap (H1). Non-JSON / non-object bodies are rejected with 0 outbound (H2).
 */
final class ReservationEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Reserved when the request omits the protocol output cap (hard ceiling for uncapped calls). */
    private static final long OUTPUT_FLOOR = 256;
    /** Practical reserve ceiling for declared output caps. */
    static final long OUTPUT_RESERVE_CAP = 32_768;

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
        if (body == null || body.length == 0) {
            throw new ForbiddenException("受管网关仅接受 JSON 对象请求体");
        }
        ObjectNode obj;
        try {
            JsonNode root = JSON.readTree(body);
            if (!(root instanceof ObjectNode objectNode)) {
                throw new ForbiddenException("受管网关仅接受 JSON 对象请求体");
            }
            obj = objectNode;
        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            throw new ForbiddenException("受管网关仅接受 JSON 对象请求体");
        }

        String field = preferredOutputField(protocol, path, obj);
        long declared = positiveNumber(obj.get(field));
        long output = declared > 0 ? Math.min(OUTPUT_RESERVE_CAP, declared) : OUTPUT_FLOOR;
        // Never raise a caller-declared cap (P2); only clamp down to the reserve ceiling / inject floor.
        boolean changed = applyOutputCap(obj, field, output);
        byte[] outbound = changed ? writeBytes(obj) : body;
        long input = Math.max(1, (body.length + 3) / 4);
        return new Prepared(input + output, outbound, output);
    }

    /**
     * Clamp or inject only the protocol-bound field. Foreign output-cap fields are ignored so a
     * Chat Completions call cannot under-reserve via {@code max_output_tokens}, etc.
     */
    private static boolean applyOutputCap(ObjectNode obj, String field, long output) {
        JsonNode n = obj.get(field);
        if (n != null && n.isNumber() && n.asLong() == output) {
            return false;
        }
        obj.put(field, output);
        return true;
    }

    /**
     * Unique legal output field for this protocol+path. For OpenAI Chat Completions both
     * {@code max_tokens} and {@code max_completion_tokens} are valid; prefer the one already present.
     */
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

    private static long positiveNumber(JsonNode n) {
        if (n != null && n.isNumber() && n.asLong() > 0) {
            return n.asLong();
        }
        return 0;
    }

    private static byte[] writeBytes(ObjectNode obj) {
        try {
            return JSON.writeValueAsBytes(obj);
        } catch (Exception e) {
            throw new ForbiddenException("受管网关仅接受 JSON 对象请求体");
        }
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
