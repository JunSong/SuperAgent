package com.superagent.core.gateway;

import java.time.Instant;

/** Idempotent settlement projection line (#34): one attempt, one lineNo. */
public record GatewayProjectionEvent(
        String id,
        String attemptId,
        String eventType,
        int lineNo,
        String payloadDigest,
        String payerUserId,
        String cli,
        String model,
        String turnId,
        String runId,
        long actualTokens,
        Instant at) {
}
