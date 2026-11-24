package com.superagent.gateway;

import com.superagent.core.gateway.GatewayPolicy;
import com.superagent.core.gateway.GatewayReportMode;
import com.superagent.core.settings.SystemSettingStore;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Company-scoped gateway policy on {@link SystemSettingStore} (#34).
 * Missing keys fall back to {@link GatewayPolicy#defaults()}.
 */
@Service
public class GatewayPolicyService {

    static final String PENDING_ALERT_HOURS = "gateway.pending.alert-after-hours";
    static final String PENDING_TAKEOVER_DAYS = "gateway.pending.company-takeover-after-business-days";
    static final String RECON_RESERVE_TARGET = "gateway.recon-reserve.target-tokens";
    static final String REPORT_MODE = "gateway.report.mode";

    private final SystemSettingStore store;

    public GatewayPolicyService(SystemSettingStore store) {
        this.store = store;
    }

    public GatewayPolicy get() {
        Map<String, String> all = store.all();
        return new GatewayPolicy(
                intVal(all, PENDING_ALERT_HOURS, GatewayPolicy.DEFAULT_PENDING_ALERT_HOURS, 1, 168),
                intVal(all, PENDING_TAKEOVER_DAYS, GatewayPolicy.DEFAULT_PENDING_TAKEOVER_BUSINESS_DAYS, 1, 30),
                longVal(all, RECON_RESERVE_TARGET, GatewayPolicy.DEFAULT_RECON_RESERVE_TARGET_TOKENS, 0, 1_000_000_000L),
                modeVal(all.get(REPORT_MODE)));
    }

    /**
     * Partial update: null fields keep the current value.
     */
    public GatewayPolicy update(Integer pendingAlertAfterHours,
                                Integer pendingCompanyTakeoverAfterBusinessDays,
                                Long reconReserveTargetTokens,
                                GatewayReportMode reportMode) {
        GatewayPolicy cur = get();
        int alert = pendingAlertAfterHours == null ? cur.pendingAlertAfterHours()
                : boundedInt(pendingAlertAfterHours, 1, 168, "pending alert hours");
        int days = pendingCompanyTakeoverAfterBusinessDays == null ? cur.pendingCompanyTakeoverAfterBusinessDays()
                : boundedInt(pendingCompanyTakeoverAfterBusinessDays, 1, 30, "pending takeover business days");
        long reserve = reconReserveTargetTokens == null ? cur.reconReserveTargetTokens()
                : boundedLong(reconReserveTargetTokens, 0, 1_000_000_000L, "recon reserve target");
        GatewayReportMode mode = reportMode == null ? cur.reportMode() : reportMode;

        store.put(PENDING_ALERT_HOURS, String.valueOf(alert));
        store.put(PENDING_TAKEOVER_DAYS, String.valueOf(days));
        store.put(RECON_RESERVE_TARGET, String.valueOf(reserve));
        store.put(REPORT_MODE, mode.name());
        return get();
    }

    private static GatewayReportMode modeVal(String raw) {
        if (raw == null || raw.isBlank()) {
            return GatewayPolicy.DEFAULT_REPORT_MODE;
        }
        try {
            return GatewayReportMode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GatewayPolicy.DEFAULT_REPORT_MODE;
        }
    }

    private static int intVal(Map<String, String> all, String key, int fallback, int min, int max) {
        try {
            String v = all.get(key);
            if (v == null || v.isBlank()) {
                return fallback;
            }
            int n = Integer.parseInt(v.strip());
            return n < min || n > max ? fallback : n;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longVal(Map<String, String> all, String key, long fallback, long min, long max) {
        try {
            String v = all.get(key);
            if (v == null || v.isBlank()) {
                return fallback;
            }
            long n = Long.parseLong(v.strip());
            return n < min || n > max ? fallback : n;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int boundedInt(int value, int min, int max, String what) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(what + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static long boundedLong(long value, long min, long max, String what) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(what + " must be between " + min + " and " + max);
        }
        return value;
    }
}
