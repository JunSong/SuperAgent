package com.superagent.core.gateway;

import java.util.Optional;

/**
 * Transactional token ledger for managed model calls (#34 PR-03): reserve → beginSend → settle.
 * Every method is for the current {@link com.superagent.core.tenant.TenantContext} company.
 * Float (F) is not used here (F=0).
 */
public interface GatewayMetering {

    /**
     * Creates or replaces a LIMITED employee account for the period with the given available balance
     * (admin / tests / future allocation API). Reserved and settled start at 0 when creating fresh.
     */
    void putEmployee(String period, String userId, long available);

    Optional<GatewayAccount> findEmployee(String period, String userId);

    /**
     * Freezes {@code reservedTokens} on the payer's LIMITED employee account for this period.
     * No-op path: callers that see empty {@link #findEmployee} treat the tenant as legacy/unlimited.
     *
     * @throws GatewayQuotaInsufficientException when available &lt; reservedTokens
     */
    GatewayAttempt reserve(ReserveRequest request);

    /** Conditional {@code RESERVED → SENDING}. Returns false when the attempt is gone or already moved. */
    boolean beginSend(String attemptId);

    /**
     * Settles with actual usage A: credits {@code reserved − A} back to available (A may not exceed R in PR-03;
     * A &gt; R is recorded as pending for later reconciliation). Idempotent for the same attempt id and A.
     */
    GatewayAttempt settle(String attemptId, long actualTokens);

    /** Keeps the freeze and marks the attempt pending when final usage is unknown. */
    GatewayAttempt markPending(String attemptId);

    /** Releases a not-yet-sent reservation (cancel before upstream). */
    void cancel(String attemptId);

    record ReserveRequest(
            String requestId,
            String turnId,
            String runId,
            String payerUserId,
            String cli,
            String model,
            String period,
            long reservedTokens) {
    }
}
