package com.superagent.core.gateway;

/** Lifecycle of one model outbound attempt (#34): reserved → sending → settled | pending_reconciliation. */
public enum GatewayAttemptStatus {
    RESERVED,
    SENDING,
    SETTLED,
    PENDING_RECONCILIATION,
    CANCELLED
}
