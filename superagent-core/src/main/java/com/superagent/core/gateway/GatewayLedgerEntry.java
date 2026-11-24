package com.superagent.core.gateway;

/** One line in {@code gateway_ledger} for audit / idempotent ops. */
public record GatewayLedgerEntry(
        String operationId,
        int entryNo,
        String period,
        GatewayAccountType accountType,
        String ownerId,
        long deltaAvailable,
        long deltaReserved,
        long deltaSettled,
        String reason,
        String attemptId) {
}
