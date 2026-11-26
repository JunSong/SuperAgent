package com.superagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Extracts billed token totals from vendor JSON fragments (#34): same shape as {@code TokenUsage.total()}
 * — uncached input + cache read + cache write + output.
 */
final class VendorUsageParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Partial or complete usage from one JSON object; fields may arrive across SSE events. */
    record Snippet(long inputish, long output, boolean hasInput, boolean hasOutput) {
        long total() {
            return Math.max(0, inputish) + Math.max(0, output);
        }
    }

    private VendorUsageParser() {
    }

    static Optional<Snippet> parse(String json) {
        if (json == null || json.isBlank() || json.regionMatches(true, 0, "[DONE]", 0, 6)) {
            return Optional.empty();
        }
        try {
            return parse(JSON.readTree(json));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Optional<Snippet> parse(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return Optional.empty();
        }
        JsonNode usage = root.path("usage");
        if (!usage.isObject()) {
            usage = root.path("message").path("usage");
        }
        if (!usage.isObject()) {
            // OpenAI Responses: usage sits under response.usage on response.completed (R07).
            usage = root.path("response").path("usage");
        }
        if (!usage.isObject()) {
            return Optional.empty();
        }
        // OpenAI / DeepSeek chat.completions — prompt already includes cache.
        if (usage.has("prompt_tokens") || usage.has("completion_tokens")) {
            long prompt = usage.path("prompt_tokens").asLong(0);
            long completion = usage.path("completion_tokens").asLong(0);
            boolean hasIn = usage.has("prompt_tokens");
            boolean hasOut = usage.has("completion_tokens");
            if (!hasIn && !hasOut) {
                return Optional.empty();
            }
            return Optional.of(new Snippet(hasIn ? prompt : 0, hasOut ? completion : 0, hasIn, hasOut));
        }
        // Anthropic Messages / OpenAI Responses-style input_tokens + output_tokens
        if (usage.has("input_tokens") || usage.has("output_tokens")
                || usage.has("cache_read_input_tokens") || usage.has("cache_creation_input_tokens")) {
            long input = usage.path("input_tokens").asLong(0);
            long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
            long cacheWrite = usage.path("cache_creation_input_tokens").asLong(0);
            long cachedDetail = usage.path("input_tokens_details").path("cached_tokens").asLong(0);
            long output = usage.path("output_tokens").asLong(0);
            boolean hasIn = usage.has("input_tokens") || usage.has("cache_read_input_tokens")
                    || usage.has("cache_creation_input_tokens") || usage.path("input_tokens_details").isObject();
            boolean hasOut = usage.has("output_tokens");
            long inputish;
            if (usage.path("input_tokens_details").isObject() && usage.has("input_tokens")) {
                // Responses: input_tokens includes cache; split for TokenUsage.total equivalence.
                inputish = Math.max(0, input - cachedDetail) + Math.max(0, cachedDetail);
            } else {
                inputish = Math.max(0, input) + Math.max(0, cacheRead) + Math.max(0, cacheWrite);
            }
            return Optional.of(new Snippet(inputish, hasOut ? output : 0, hasIn, hasOut));
        }
        return Optional.empty();
    }
}
