package com.superagent.core.gateway;

import java.util.List;
import java.util.Optional;

/**
 * Transactional token ledger for managed model calls (#34): reserve → beginSend → settle,
 * with per-turn float (PR-04) and company budget ops (PR-05).
 * Every method is for the current {@link com.superagent.core.tenant.TenantContext} company.
 */
public interface GatewayMetering {

    /** LIMITED employee with available=baseQuota and F=0 (PR-03 style). */
    void putEmployee(String period, String userId, long available);

    /**
     * LIMITED employee for float tests / admin seeds.
     *
     * @param available      spendable now
     * @param settled        already spent (counts toward B)
     * @param baseQuota      B
     * @param floatAllowance F (0 disables float)
     */
    void putEmployee(String period, String userId, long available, long settled, long baseQuota, long floatAllowance);

    /** Company pool (UNALLOCATED / FLOAT_POOL / RECON_RESERVE / SYSTEM). Owner is empty for company pools. */
    void putPool(String period, GatewayAccountType type, long available);

    /**
     * Top up {@link GatewayAccountType#RECON_RESERVE} toward {@code target} by transferring from
     * {@link GatewayAccountType#UNALLOCATED}. Never mints: moves {@code min(deficit, unallocated)},
     * writes ledger lines, and is safe to retry (no REPLACE). Returns tokens actually moved.
     */
    default long fundReconReserveToTarget(String period, long target) {
        return 0L;
    }

    Optional<GatewayAccount> findEmployee(String period, String userId);

    Optional<GatewayAccount> findAccount(String period, GatewayAccountType type, String ownerId);

    /** Employee accounts for the period (owner id ascending). Empty default for in-memory test stubs. */
    default List<GatewayAccount> listEmployees(String period) {
        return List.of();
    }

    /**
     * Freezes {@code reservedTokens}. May atomically claim float for {@code turnId} when crossing B.
     *
     * @throws GatewayQuotaExhaustedException when used ≥ B and this turn did not claim float
     * @throws GatewayQuotaInsufficientException when the effective cap or available cannot cover R
     */
    GatewayAttempt reserve(ReserveRequest request);

    boolean beginSend(String attemptId);

    /**
     * Records full vendor usage A (never silently truncated). Employee pays {@code min(A,R)} from the
     * freeze; {@code A>R} is debited from company {@link GatewayAccountType#RECON_RESERVE} in the same
     * transaction. Writes an idempotent settlement projection with A.
     * Allowed from SENDING or PENDING_RECONCILIATION (admin reconcile) — not RESERVED.
     */
    GatewayAttempt settle(String attemptId, long actualTokens);

    GatewayAttempt markPending(String attemptId);

    /**
     * Like {@link #markPending(String)} but persists a known vendor total A when settle cannot finish
     * (e.g. RECON_RESERVE short). {@code actualTokens < 0} leaves the stored A unchanged.
     */
    default GatewayAttempt markPending(String attemptId, long actualTokens) {
        return markPending(attemptId);
    }

    void cancel(String attemptId);

    /**
     * Abort a known-unsent attempt after {@link #beginSend} claimed send authority but before any
     * HTTP dispatch. Releases the freeze from {@link GatewayAttemptStatus#RESERVED} or
     * {@link GatewayAttemptStatus#SENDING}. Idempotent on {@link GatewayAttemptStatus#CANCELLED};
     * no-op for {@link GatewayAttemptStatus#SETTLED} / {@link GatewayAttemptStatus#PENDING_RECONCILIATION}
     * (must not refund after outbound may have occurred). Default no-op for test stubs.
     */
    default void abortUnsent(String attemptId) {
    }

    /** Attempts in {@code status} for the period (newest first). Empty default for in-memory test stubs. */
    default List<GatewayAttempt> listAttempts(GatewayAttemptStatus status, String period, int limit) {
        return List.of();
    }

    /** Settlement projection lines for the period (audit / report replay). */
    default List<GatewayProjectionEvent> listProjections(String period, int limit) {
        return List.of();
    }

    /**
     * Admin: move {@code amount} from UNALLOCATED → employee available (increases B).
     * Idempotent on {@code operationId} with the same parameters.
     */
    GatewayAccount allocate(String operationId, String period, String toUserId, long amount, String actorUserId);

    /**
     * Admin: set employee float allowance F (0 disables float for that person).
     * Does not change available / base quota.
     */
    GatewayAccount setFloatAllowance(String period, String userId, long floatAllowance);

    /**
     * Admin: set F={@code floatAllowance} on every employee in the period whose current F is 0.
     *
     * @return how many accounts were updated
     */
    int applyDefaultFloat(String period, long floatAllowance);

    /** Admin: move {@code amount} from employee available → UNALLOCATED (decreases B). */
    GatewayAccount reclaim(String operationId, String period, String fromUserId, long amount, String actorUserId);

    /** Same-company employee transfer of available tokens. */
    void transfer(String operationId, String period, String fromUserId, String toUserId, long amount, String actorUserId);

    /** Recent ledger lines for the company (audit). */
    List<GatewayLedgerEntry> listLedger(String period, int limit);

    /**
     * Read-only: whether this turn already has a live attempt (SETTLED / SENDING / PENDING_RECON).
     * Used by the proxy pre-count gate so same-turn float continuation is not blocked when
     * {@code available=0 && reserved=0} after base is exactly spent. Default false (test stubs).
     */
    default boolean hasAttemptForTurn(String period, String payerUserId, String turnId) {
        return false;
    }

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
