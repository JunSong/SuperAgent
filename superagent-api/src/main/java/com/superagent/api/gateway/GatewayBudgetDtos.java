package com.superagent.api.gateway;

import java.util.List;

/** Request/response shapes for model-gateway budget APIs (#34 PR-05). */
public final class GatewayBudgetDtos {

    private GatewayBudgetDtos() {
    }

    public record Balance(
            String period,
            String owner,
            String type,
            long available,
            long reserved,
            long settled,
            long baseQuota,
            long floatAllowance,
            String floatTurnId) {
    }

    /** All employee balances for a period (admin roster). */
    public record BalancePage(List<Balance> balances) {
    }

    public record LedgerLine(
            String operationId,
            int entryNo,
            String period,
            String accountType,
            String ownerId,
            long deltaAvailable,
            long deltaReserved,
            long deltaSettled,
            String reason) {
    }

    /**
     * @param floatAllowance optional; null → apply company default when the employee still has F=0
     */
    public record AllocateRequest(String operationId, String period, String userId, long amount, Long floatAllowance) {
    }

    public record ReclaimRequest(String operationId, String period, String userId, long amount) {
    }

    /** Admin sets one employee's float allowance F. */
    public record SetFloatRequest(String period, String userId, long floatAllowance) {
    }

    /** Admin applies company default F to every employee in the period with F=0. */
    public record ApplyFloatDefaultsRequest(String period) {
    }

    public record ApplyFloatDefaultsResult(int updated, long floatAllowance) {
    }

    public record TransferRequest(String operationId, String period, String toUserId, long amount) {
    }

    public record PutPoolRequest(String period, String type, long available) {
    }

    public record LedgerPage(List<LedgerLine> lines) {
    }

    public record AttemptLine(
            String id,
            String turnId,
            String runId,
            String payerUserId,
            String cli,
            String model,
            String period,
            long reservedTokens,
            long actualTokens,
            String status) {
    }

    public record AttemptPage(List<AttemptLine> attempts) {
    }

    /** Admin closes a pending_reconciliation attempt with vendor evidence A. */
    public record ReconcileRequest(String attemptId, long actualTokens) {
    }

    public record ProjectionLine(
            String id,
            String attemptId,
            String eventType,
            int lineNo,
            String payerUserId,
            String cli,
            String model,
            long actualTokens) {
    }

    public record ProjectionPage(List<ProjectionLine> projections) {
    }

    /**
     * Company gateway policy. Null fields on update keep the current value.
     * Defaults: alert 1h, takeover 2 business days, recon reserve target 500k, float F 100k,
     * reportMode LEGACY, managedClis empty (metering off until the admin enables CLIs).
     */
    public record Policy(
            int pendingAlertAfterHours,
            int pendingCompanyTakeoverAfterBusinessDays,
            long reconReserveTargetTokens,
            long defaultFloatAllowance,
            String reportMode,
            List<String> managedClis) {
    }

    public record PolicyUpdate(
            Integer pendingAlertAfterHours,
            Integer pendingCompanyTakeoverAfterBusinessDays,
            Long reconReserveTargetTokens,
            Long defaultFloatAllowance,
            String reportMode,
            List<String> managedClis) {
    }
}
