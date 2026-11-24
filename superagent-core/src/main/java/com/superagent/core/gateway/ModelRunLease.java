package com.superagent.core.gateway;

/**
 * A short-lived credential for one CLI run to call the control-plane model gateway.
 * The CLI gets {@link #gatewayBaseUrl()} and {@link #token()} only — never the company API key.
 */
public record ModelRunLease(
        String token,
        String gatewayBaseUrl,
        String runId,
        String turnId,
        String cli,
        String model) {
}
