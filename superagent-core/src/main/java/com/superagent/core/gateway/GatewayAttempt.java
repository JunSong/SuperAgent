package com.superagent.core.gateway;

/**
 * One outbound model attempt under a run token. {@code requestId} + {@code attemptNo} are unique per company.
 *
 * @param reservedTokens R frozen at reserve time
 * @param actualTokens   A after settle; 0 until settled
 */
public record GatewayAttempt(
        String id,
        String requestId,
        int attemptNo,
        String turnId,
        String runId,
        String payerUserId,
        String cli,
        String model,
        String period,
        long reservedTokens,
        long actualTokens,
        GatewayAttemptStatus status) {
}
