package com.superagent.core.gateway;

import com.superagent.core.chat.RunCredential;

/**
 * A {@link RunCredential} for one CLI process, plus the opaque token to revoke when the process ends.
 * When {@link #managed()} is false, {@link #revokeToken()} is null and the credential may hold a company Key
 * (legacy path).
 */
public record ManagedRun(RunCredential credential, String revokeToken, boolean managed) implements AutoCloseable {

    public static ManagedRun legacy(RunCredential credential) {
        return new ManagedRun(credential, null, false);
    }

    public static ManagedRun ofLease(ModelRunLease lease) {
        return new ManagedRun(
                new RunCredential(lease.runId(), lease.gatewayBaseUrl(), lease.token(), true),
                lease.token(),
                true);
    }

    @Override
    public void close() {
        // Callers that hold a {@link ModelRunLeaseService} should revoke explicitly; this is a marker for try-with-resources
        // at sites that capture the service. Prefer {@link #close(ModelRunLeaseService)}.
    }

    public void close(ModelRunLeaseService leases) {
        if (leases != null && revokeToken != null) {
            leases.revoke(revokeToken);
        }
    }
}
