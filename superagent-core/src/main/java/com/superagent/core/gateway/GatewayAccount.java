package com.superagent.core.gateway;

/**
 * One company's token account for a UTC month period. Every call is for {@link com.superagent.core.tenant.TenantContext}.
 *
 * @param available      tokens free to reserve
 * @param reserved       tokens frozen on in-flight attempts
 * @param settled        tokens already settled as spent this period
 * @param baseQuota      B — monthly base cap before float
 * @param floatAllowance F — extra tokens only the float-claiming turn may use
 * @param floatTurnId    the unique turn that claimed F; null if none
 */
public record GatewayAccount(
        String period,
        GatewayAccountType type,
        String owner,
        GatewayQuotaMode quotaMode,
        long available,
        long reserved,
        long settled,
        long baseQuota,
        long floatAllowance,
        String floatTurnId) {
}
