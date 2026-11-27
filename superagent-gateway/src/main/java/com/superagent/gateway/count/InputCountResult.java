package com.superagent.gateway.count;

import com.superagent.core.gateway.InputCountSource;

/**
 * Pre-request input token estimate used for reservation R (output cap is separate).
 */
public record InputCountResult(long inputTokens, InputCountSource source, String detail) {

    public InputCountResult {
        if (inputTokens < 1) {
            throw new IllegalArgumentException("inputTokens must be >= 1");
        }
        if (source == null) {
            throw new IllegalArgumentException("source");
        }
        detail = detail == null ? "" : detail;
    }

    public static InputCountResult heuristic(long tokens) {
        return new InputCountResult(tokens, InputCountSource.HEURISTIC, "body_bytes/4");
    }

    public static InputCountResult provider(long tokens, String detail) {
        return new InputCountResult(tokens, InputCountSource.PROVIDER, detail);
    }

    public static InputCountResult localEstimate(long tokens, String detail) {
        return new InputCountResult(tokens, InputCountSource.LOCAL_ESTIMATE, detail);
    }
}
