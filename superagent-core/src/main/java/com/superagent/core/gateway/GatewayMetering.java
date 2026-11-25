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
     * Records actual usage A (capped at R) and writes an idempotent settlement projection line.
     * Allowed from SENDING or PENDING_RECONCILIATION (admin reconcile).
     */
    GatewayAttempt settle(String attemptId, long actualTokens);

    GatewayAttempt markPending(String attemptId);

    void cancel(String attemptId);

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
