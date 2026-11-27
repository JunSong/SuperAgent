package com.superagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.gateway.GatewayInputBoundMode;
import com.superagent.core.gateway.InputCountSource;
import com.superagent.gateway.count.InputCountResult;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Builds reservation R = input estimate + clamped output cap. Input comes from
 * {@link InputCountResult} (LiteLLM-aligned A/C); when omitted, falls back to heuristic
 * {@code body bytes/4}.
 *
 * <p>{@code declared} comes only from the protocol-appropriate field (H1). Every known output-cap
 * field is then clamped to that same {@code output} so dual-field requests cannot under-reserve
 * while a foreign field still reaches the vendor (NEW-2). Non-JSON / non-object bodies are
 * rejected with 0 outbound (H2).
 */
final class ReservationEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Reserved when the request omits the protocol output cap (hard ceiling for uncapped calls). */
    private static final long OUTPUT_FLOOR = 256;
    /** Practical reserve ceiling for declared output caps. */
    static final long OUTPUT_RESERVE_CAP = 32_768;
    private static final String[] OUTPUT_FIELDS = {"max_tokens", "max_output_tokens", "max_completion_tokens"};

    record Prepared(long reservedTokens, byte[] body, long outputCap, GatewayInputBoundMode inputBoundMode,
                    InputCountSource inputCountSource) {
        Prepared(long reservedTokens, byte[] body, long outputCap, GatewayInputBoundMode inputBoundMode) {
            this(reservedTokens, body, outputCap, inputBoundMode, InputCountSource.HEURISTIC);
        }
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
        return prepare(body, protocol, path, null, GatewayInputBoundMode.HEURISTIC);
    }

    record Clamped(byte[] body, long outputCap) {
    }

    /** Output-cap clamp only (no reservation math). */
    static Clamped clamp(byte[] body, String protocol, String path) {
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
        boolean changed = applyOutputCap(obj, field, output);
        byte[] outbound = changed ? writeBytes(obj) : body;
        return new Clamped(outbound, output);
    }

    static Prepared prepare(byte[] body, String protocol, String path, InputCountResult inputCount,
                            GatewayInputBoundMode boundMode) {
        Clamped clamped = clamp(body, protocol, path);
        InputCountResult counted = inputCount != null
                ? inputCount
                : InputCountResult.heuristic(Math.max(1, (body.length + 3) / 4));
        GatewayInputBoundMode mode = boundMode == null ? GatewayInputBoundMode.HEURISTIC : boundMode;
        // Prefer honest labeling: if caller asked PROVEN but count fell back, keep HEURISTIC.
        if (mode == GatewayInputBoundMode.PROVEN && counted.source() != InputCountSource.PROVIDER) {
            mode = GatewayInputBoundMode.HEURISTIC;
        }
        return new Prepared(counted.inputTokens() + clamped.outputCap(), clamped.body(), clamped.outputCap(),
                mode, counted.source());
    }

    /**
     * Set the protocol field to {@code output}, and clamp every other known output-cap field that is
     * already present so a dual-field body cannot under-reserve while a large foreign cap reaches
     * the vendor.
     */
    private static boolean applyOutputCap(ObjectNode obj, String preferred, long output) {
        boolean changed = false;
        JsonNode preferredNode = obj.get(preferred);
        if (preferredNode == null || !preferredNode.isNumber() || preferredNode.asLong() != output) {
            obj.put(preferred, output);
            changed = true;
        }
        for (String f : OUTPUT_FIELDS) {
            if (f.equals(preferred) || !obj.has(f)) {
                continue;
            }
            JsonNode n = obj.get(f);
            if (!n.isNumber() || n.asLong() != output) {
                obj.put(f, output);
                changed = true;
            }
        }
        return changed;
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
