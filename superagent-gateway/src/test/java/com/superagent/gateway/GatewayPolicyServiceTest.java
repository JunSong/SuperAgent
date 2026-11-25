package com.superagent.gateway;

import com.superagent.core.gateway.GatewayPolicy;
import com.superagent.core.gateway.GatewayReportMode;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayPolicyServiceTest {

    @Test
    void defaultsWhenUnset() {
        GatewayPolicyService svc = new GatewayPolicyService(new MemStore());
        TenantContext.run("co1", () -> {
            assertThat(svc.get()).isEqualTo(GatewayPolicy.defaults());
            assertThat(svc.get().reportMode()).isEqualTo(GatewayReportMode.LEGACY);
            assertThat(svc.get().reconReserveTargetTokens())
                    .isEqualTo(GatewayPolicy.DEFAULT_RECON_RESERVE_TARGET_TOKENS);
            assertThat(svc.get().defaultFloatAllowance())
                    .isEqualTo(GatewayPolicy.DEFAULT_FLOAT_ALLOWANCE);
            assertThat(svc.get().managedClis()).isEmpty();
        });
    }

    @Test
    void envManagedClisUsedUntilCompanySaves() {
        GatewayPolicyService svc = new GatewayPolicyService(new MemStore(), "deepseek,claude");
        TenantContext.run("co1", () -> {
            assertThat(svc.get().managedClis()).containsExactly("deepseek", "claude");
            assertThat(svc.managed("DeepSeek")).isTrue();
            assertThat(svc.managed("codex")).isFalse();

            GatewayPolicy saved = svc.update(null, null, null, null, null, List.of("codex"));
            assertThat(saved.managedClis()).containsExactly("codex");
            assertThat(svc.managed("deepseek")).isFalse();
            assertThat(svc.managed("codex")).isTrue();
        });
    }

    @Test
    void emptyManagedClisPersistsAndMeansNone() {
        GatewayPolicyService svc = new GatewayPolicyService(new MemStore(), "deepseek");
        TenantContext.run("co1", () -> {
            assertThat(svc.managed("deepseek")).isTrue();
            svc.update(null, null, null, null, null, List.of());
            assertThat(svc.get().managedClis()).isEmpty();
            assertThat(svc.managed("deepseek")).isFalse();
        });
    }

    @Test
    void updatePersistsAndRejectsOutOfRange() {
        GatewayPolicyService svc = new GatewayPolicyService(new MemStore());
        TenantContext.run("co1", () -> {
            GatewayPolicy updated = svc.update(3, 5, 100_000L, 50_000L, GatewayReportMode.SHADOW, List.of("deepseek"));
            assertThat(updated.pendingAlertAfterHours()).isEqualTo(3);
            assertThat(updated.pendingCompanyTakeoverAfterBusinessDays()).isEqualTo(5);
            assertThat(updated.reconReserveTargetTokens()).isEqualTo(100_000L);
            assertThat(updated.defaultFloatAllowance()).isEqualTo(50_000L);
            assertThat(updated.reportMode()).isEqualTo(GatewayReportMode.SHADOW);
            assertThat(updated.managedClis()).containsExactly("deepseek");
            assertThat(svc.get()).isEqualTo(updated);

            assertThatThrownBy(() -> svc.update(0, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(svc.update(null, null, null, null, GatewayReportMode.GATEWAY_PRIMARY, null).reportMode())
                    .isEqualTo(GatewayReportMode.GATEWAY_PRIMARY);
            assertThatThrownBy(() -> svc.update(null, null, null, null, null, List.of("not-a-cli")))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    /** Keeps blank values so "saved empty managed-clis" differs from "never set". */
    static final class MemStore implements SystemSettingStore {
        private final Map<String, String> map = new ConcurrentHashMap<>();

        @Override
        public Map<String, String> all() {
            return Map.copyOf(map);
        }

        @Override
        public void put(String key, String value) {
            map.put(key, value == null ? "" : value);
        }
    }
}
