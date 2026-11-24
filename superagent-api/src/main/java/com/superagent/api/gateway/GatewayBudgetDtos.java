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

    public record AllocateRequest(String operationId, String period, String userId, long amount) {
    }

    public record ReclaimRequest(String operationId, String period, String userId, long amount) {
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
}
