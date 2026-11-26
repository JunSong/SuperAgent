package com.superagent.core.gateway;

/**
 * How the gateway forms the input half of reservation R (#34 option C).
 *
 * <p>{@link #HEURISTIC} is the default soft path ({@code body bytes/4}): monthly B/F still applies,
 * output caps are forced, but the company must not promise employees that spend cannot exceed the
 * vendor bill. {@link #PROVEN} is reserved for G0-backed tokenizer / count evidence — not available
 * until that evidence exists for a CLI.
 */
public enum GatewayInputBoundMode {
    HEURISTIC,
    PROVEN
}
