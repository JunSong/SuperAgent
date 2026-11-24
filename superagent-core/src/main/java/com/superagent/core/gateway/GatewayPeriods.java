package com.superagent.core.gateway;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

/** UTC calendar-month period keys for the gateway ledger ({@code yyyy-MM}). */
public final class GatewayPeriods {

    private GatewayPeriods() {
    }

    public static String current(Clock clock) {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
    }
}
