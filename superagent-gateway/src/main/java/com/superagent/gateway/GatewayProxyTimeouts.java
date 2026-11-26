package com.superagent.gateway;

import java.time.Duration;

/**
 * Bounded upstream timings for the model gateway (#34 §4 / tech-design).
 * Defaults: connect 5s, first-byte 30s, idle 120s, whole request 600s.
 */
record GatewayProxyTimeouts(
        Duration connect,
        Duration firstByte,
        Duration idle,
        Duration request) {

    static final GatewayProxyTimeouts DEFAULTS = new GatewayProxyTimeouts(
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofSeconds(120),
            Duration.ofSeconds(600));

    /** Generous ceilings for unit tests that use a local stub. */
    static final GatewayProxyTimeouts TESTS = new GatewayProxyTimeouts(
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofSeconds(300),
            Duration.ofMinutes(30));

    GatewayProxyTimeouts {
        connect = requirePositive(connect, "connect");
        firstByte = requirePositive(firstByte, "firstByte");
        idle = requirePositive(idle, "idle");
        request = requirePositive(request, "request");
    }

    static GatewayProxyTimeouts ofSeconds(long connect, long firstByte, long idle, long request) {
        return new GatewayProxyTimeouts(
                Duration.ofSeconds(Math.max(1, connect)),
                Duration.ofSeconds(Math.max(1, firstByte)),
                Duration.ofSeconds(Math.max(1, idle)),
                Duration.ofSeconds(Math.max(1, request)));
    }

    private static Duration requirePositive(Duration d, String name) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " timeout must be > 0");
        }
        return d;
    }
}
