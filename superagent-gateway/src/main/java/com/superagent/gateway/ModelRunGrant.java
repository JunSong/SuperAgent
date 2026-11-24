package com.superagent.gateway;

import java.time.Instant;

/**
 * Server-side record for one opaque run token. The CLI never sees companyId or the pool key.
 */
public record ModelRunGrant(
        String token,
        String companyId,
        String payerUserId,
        String turnId,
        String runId,
        String cli,
        String model,
        Instant expiresAt,
        boolean revoked) {

    public ModelRunGrant revoke() {
        return new ModelRunGrant(token, companyId, payerUserId, turnId, runId, cli, model, expiresAt, true);
    }

    public boolean usable(Instant now) {
        return !revoked && now.isBefore(expiresAt);
    }
}
