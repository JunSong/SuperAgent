package com.superagent.gateway;

import com.superagent.core.gateway.GatewayPolicy;
import com.superagent.core.gateway.GatewayReportMode;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;

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
        });
    }

    @Test
    void updatePersistsAndRejectsOutOfRange() {
        GatewayPolicyService svc = new GatewayPolicyService(new MemStore());
        TenantContext.run("co1", () -> {
            GatewayPolicy updated = svc.update(3, 5, 100_000L, GatewayReportMode.SHADOW);
            assertThat(updated.pendingAlertAfterHours()).isEqualTo(3);
            assertThat(updated.pendingCompanyTakeoverAfterBusinessDays()).isEqualTo(5);
            assertThat(updated.reconReserveTargetTokens()).isEqualTo(100_000L);
            assertThat(updated.reportMode()).isEqualTo(GatewayReportMode.SHADOW);
            assertThat(svc.get()).isEqualTo(updated);

            assertThatThrownBy(() -> svc.update(0, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(svc.update(null, null, null, GatewayReportMode.GATEWAY_PRIMARY).reportMode())
                    .isEqualTo(GatewayReportMode.GATEWAY_PRIMARY);
        });
    }

    static final class MemStore implements SystemSettingStore {
        private final Map<String, String> map = new ConcurrentHashMap<>();

        @Override
        public Map<String, String> all() {
            return Map.copyOf(map);
        }

        @Override
        public void put(String key, String value) {
            if (value == null || value.isBlank()) {
                map.remove(key);
            } else {
                map.put(key, value);
            }
        }
    }
}
