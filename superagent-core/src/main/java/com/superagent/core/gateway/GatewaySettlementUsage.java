package com.superagent.core.gateway;

import com.superagent.core.usage.UsageRecord;

import java.time.Instant;
import java.util.List;

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
}
