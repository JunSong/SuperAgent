package com.superagent.core.gateway;

import com.superagent.core.usage.UsageRecord;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Settlement projections as {@link UsageRecord} rows for report aggregation (#34).
 * Implemented in storage next to the ledger; cost reads this when report mode is
 * {@link GatewayReportMode#GATEWAY_PRIMARY}.
 */
public interface GatewaySettlementUsage {

    /**
     * Settled gateway attempts in {@code [from, to)}.
     *
     * @param user payer user id, or null for the whole company
     */
    List<UsageRecord> between(String user, Instant from, Instant to);

    /**
     * Settlements for the given run ids, regardless of time window (cli + payer included for shadow matching).
     * Implementations must not silently drop candidates past a fixed size — batch the IN query instead.
     *
     * @param user payer user id, or null for the whole company
     */
    default List<UsageRecord> settlementsForRunIds(String user, Collection<String> runIds) {
        throw new UnsupportedOperationException(
                "settlementsForRunIds must be implemented for GATEWAY_PRIMARY CLI shadowing");
    }

    /**
     * Run ids among {@code runIds} that already have at least one settlement, regardless of time window.
     * Prefer {@link #settlementsForRunIds} when cli/user must be checked.
     */
    default Set<String> settledRunIds(String user, Collection<String> runIds) {
        return settlementsForRunIds(user, runIds).stream()
                .map(UsageRecord::runId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
