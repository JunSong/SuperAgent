package com.superagent.core.gateway;

import java.time.Duration;

/**
 * Issues and revokes per-run tokens that authorize a CLI to call the model gateway.
 * Implementations live in {@code superagent-gateway}; chat/task call this port from core.
 */
public interface ModelRunLeaseService {

    /**
     * Whether this CLI should talk to the gateway with a run token instead of a company key.
     * When false, callers keep the legacy credential injection path.
     */
    boolean managed(String cli);

    /**
     * Opens a lease for one CLI process. Uses {@link com.superagent.core.tenant.TenantContext}
     * for the company. {@code turnId} is the chat {@code userMessageId} or the automation {@code taskId}.
     */
    ModelRunLease issue(String turnId, String runId, String payerUserId, String cli, String model, Duration ttl);

    /** Revokes one token immediately (e.g. after {@code runOnce}). */
    void revoke(String token);

    /** Revokes every token issued for this run id. */
    void revokeRun(String runId);
}
