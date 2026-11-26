package com.superagent.core.usage;

import java.time.Instant;

/**
 * One model's tokens in one run, on the person who pays. {@code costUsd} is the estimate at the time, from list
 * prices (null when the model has no known price); {@code cliCostUsd} what the CLI said, when it says;
 * {@code contextTokens} the largest context a single request read (0 when the CLI doesn't say), which is what tells
 * how big the conversation actually was, unlike the sum over its requests.
 *
 * <p>{@code runId} is the managed-gateway run identity for settlement shadowing; {@code sessionId} stays the
 * chat/task session key (never the run id).
 */
public record UsageRecord(String id, Instant at, String user, String source, String agentId, String taskId,
                          String sessionId, String cli, String model, long input, long cacheRead, long cacheWrite,
                          long cacheWrite1h, long output, long reasoning, long contextTokens, Double costUsd,
                          Double cliCostUsd, String runId) {

    /** Rows / callers without a managed run id. */
    public UsageRecord(String id, Instant at, String user, String source, String agentId, String taskId,
                       String sessionId, String cli, String model, long input, long cacheRead, long cacheWrite,
                       long cacheWrite1h, long output, long reasoning, long contextTokens, Double costUsd,
                       Double cliCostUsd) {
        this(id, at, user, source, agentId, taskId, sessionId, cli, model, input, cacheRead, cacheWrite,
                cacheWrite1h, output, reasoning, contextTokens, costUsd, cliCostUsd, null);
    }

    public long total() {
        return input + cacheRead + cacheWrite + output;
    }
}
