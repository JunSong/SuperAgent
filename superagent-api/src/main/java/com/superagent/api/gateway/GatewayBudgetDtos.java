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
}
