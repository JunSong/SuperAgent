package com.superagent.core.gateway;

/**
 * Per-company model-gateway policy (#34). Defaults are open-source sane values;
 * each deploying company edits them — there is no central product/finance gate.
 *
 * @param pendingAlertAfterHours                 hours after pending_reconciliation before operators should be alerted
 * @param pendingCompanyTakeoverAfterBusinessDays business days after which the company may take over the freeze
 * @param reconReserveTargetTokens               target size for {@link GatewayAccountType#RECON_RESERVE} (0 = no target)
 * @param reportMode                             which usage source reports treat as primary
 */
public record GatewayPolicy(
        int pendingAlertAfterHours,
        int pendingCompanyTakeoverAfterBusinessDays,
        long reconReserveTargetTokens,
        GatewayReportMode reportMode) {

    public static final int DEFAULT_PENDING_ALERT_HOURS = 1;
    public static final int DEFAULT_PENDING_TAKEOVER_BUSINESS_DAYS = 2;
    /** Enough to cover several mid-size freezes; companies raise/lower as they like. */
    public static final long DEFAULT_RECON_RESERVE_TARGET_TOKENS = 500_000L;
    public static final GatewayReportMode DEFAULT_REPORT_MODE = GatewayReportMode.LEGACY;

    public static GatewayPolicy defaults() {
        return new GatewayPolicy(
                DEFAULT_PENDING_ALERT_HOURS,
                DEFAULT_PENDING_TAKEOVER_BUSINESS_DAYS,
                DEFAULT_RECON_RESERVE_TARGET_TOKENS,
                DEFAULT_REPORT_MODE);
    }
}
