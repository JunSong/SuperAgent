package com.superagent.gateway;

import com.superagent.core.gateway.GatewayPolicy;
import com.superagent.core.gateway.GatewayReportMode;
import com.superagent.core.gateway.GatewayReportPolicy;
import com.superagent.core.provider.ProviderCatalog;
import com.superagent.core.settings.SystemSettingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Company-scoped gateway policy on {@link SystemSettingStore} (#34).
 * Missing keys fall back to {@link GatewayPolicy#defaults()}, except {@code managed-clis}:
 * when the company has never saved that key, the process env / yaml default is shown.
 */
@Service
public class GatewayPolicyService implements GatewayReportPolicy {

    static final String PENDING_ALERT_HOURS = "gateway.pending.alert-after-hours";
    static final String PENDING_TAKEOVER_DAYS = "gateway.pending.company-takeover-after-business-days";
    static final String RECON_RESERVE_TARGET = "gateway.recon-reserve.target-tokens";
    static final String DEFAULT_FLOAT = "gateway.float.default-allowance";
    static final String REPORT_MODE = "gateway.report.mode";
    static final String MANAGED_CLIS = "gateway.managed-clis";

    private final SystemSettingStore store;
    private final List<String> envManagedClis;

    @Autowired
    public GatewayPolicyService(SystemSettingStore store,
                                @Value("${superagent.gateway.managed-clis:}") String envManagedClis) {
        this.store = store;
        this.envManagedClis = parseClis(envManagedClis);
    }

    /** Tests without an env default. */
    public GatewayPolicyService(SystemSettingStore store) {
        this(store, "");
    }

    @Override
    public GatewayReportMode reportMode() {
        return get().reportMode();
    }

    public GatewayPolicy get() {
        Map<String, String> all = store.all();
        List<String> managed = all.containsKey(MANAGED_CLIS) ? parseClis(all.get(MANAGED_CLIS)) : envManagedClis;
        return new GatewayPolicy(
                intVal(all, PENDING_ALERT_HOURS, GatewayPolicy.DEFAULT_PENDING_ALERT_HOURS, 1, 168),
                intVal(all, PENDING_TAKEOVER_DAYS, GatewayPolicy.DEFAULT_PENDING_TAKEOVER_BUSINESS_DAYS, 1, 30),
                longVal(all, RECON_RESERVE_TARGET, GatewayPolicy.DEFAULT_RECON_RESERVE_TARGET_TOKENS, 0, 1_000_000_000L),
                longVal(all, DEFAULT_FLOAT, GatewayPolicy.DEFAULT_FLOAT_ALLOWANCE, 0, 1_000_000_000L),
                modeVal(all.get(REPORT_MODE)),
                managed);
    }

    /**
     * Partial update: null fields keep the current value.
     * {@code managedClis} null keeps; non-null (including empty) replaces the company list.
     */
    public GatewayPolicy update(Integer pendingAlertAfterHours,
                                Integer pendingCompanyTakeoverAfterBusinessDays,
                                Long reconReserveTargetTokens,
                                Long defaultFloatAllowance,
                                GatewayReportMode reportMode,
                                List<String> managedClis) {
        GatewayPolicy cur = get();
        int alert = pendingAlertAfterHours == null ? cur.pendingAlertAfterHours()
                : boundedInt(pendingAlertAfterHours, 1, 168, "pending alert hours");
        int days = pendingCompanyTakeoverAfterBusinessDays == null ? cur.pendingCompanyTakeoverAfterBusinessDays()
                : boundedInt(pendingCompanyTakeoverAfterBusinessDays, 1, 30, "pending takeover business days");
        long reserve = reconReserveTargetTokens == null ? cur.reconReserveTargetTokens()
                : boundedLong(reconReserveTargetTokens, 0, 1_000_000_000L, "recon reserve target");
        long floatF = defaultFloatAllowance == null ? cur.defaultFloatAllowance()
                : boundedLong(defaultFloatAllowance, 0, 1_000_000_000L, "default float allowance");
        GatewayReportMode mode = reportMode == null ? cur.reportMode() : reportMode;
        List<String> clis = managedClis == null ? cur.managedClis() : normalizeClis(managedClis);

        store.put(PENDING_ALERT_HOURS, String.valueOf(alert));
        store.put(PENDING_TAKEOVER_DAYS, String.valueOf(days));
        store.put(RECON_RESERVE_TARGET, String.valueOf(reserve));
        store.put(DEFAULT_FLOAT, String.valueOf(floatF));
        store.put(REPORT_MODE, mode.name());
        store.put(MANAGED_CLIS, String.join(",", clis));
        return get();
    }

    /** Whether {@code cli} is on this company's managed list (empty → not managed). */
    public boolean managed(String cli) {
        if (cli == null || cli.isBlank()) {
            return false;
        }
        String key = cli.strip().toLowerCase(Locale.ROOT);
        return get().managedClis().stream().anyMatch(c -> c.equalsIgnoreCase(key));
    }

    static List<String> parseClis(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return normalizeClis(Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
    }

    static List<String> normalizeClis(List<String> raw) {
        Set<String> known = ProviderCatalog.all().stream()
                .filter(ProviderCatalog.Entry::installable)
                .map(e -> e.key().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String c : raw) {
            if (c == null || c.isBlank()) {
                continue;
            }
            String key = c.strip().toLowerCase(Locale.ROOT);
            if (!known.contains(key)) {
                throw new IllegalArgumentException("unknown managed CLI: " + c);
            }
            out.add(key);
        }
        return new ArrayList<>(out);
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
