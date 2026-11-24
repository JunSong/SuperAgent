package com.superagent.core.gateway;

/**
 * Which usage source the company treats as authoritative for employee/company reports (#34).
 * Projections are always written on settle; this only chooses what the UI/API aggregates.
 */
public enum GatewayReportMode {
    /** CLI TOKENS remain the report source (safest default for open-source installs). */
    LEGACY,
    /** Keep writing gateway projections for reconciliation; reports still read CLI. */
    SHADOW,
    /** Reports prefer gateway settlement projections over CLI TOKENS. */
    GATEWAY_PRIMARY
}
