package com.superagent.gateway;

import com.superagent.core.gateway.ModelRunLease;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRunGrantsTest {

    @Test
    void twoEmployeesGetDistinctTokensSameCompany() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T00:00:00Z"));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://127.0.0.1:18080", clock);

        ModelRunLease a = TenantContext.call("co1",
                () -> grants.issue("turn-a", "run-a", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(10)));
        ModelRunLease b = TenantContext.call("co1",
                () -> grants.issue("turn-b", "run-b", "bob", "deepseek", "deepseek-chat", Duration.ofMinutes(10)));

        assertThat(a.token()).isNotEqualTo(b.token());
        assertThat(a.gatewayBaseUrl()).isEqualTo("http://127.0.0.1:18080/api/v1/gateway/model/anthropic");
        assertThat(grants.require("Bearer " + a.token()).payerUserId()).isEqualTo("alice");
        assertThat(grants.require("Bearer " + b.token()).payerUserId()).isEqualTo("bob");
        assertThat(grants.require("Bearer " + a.token()).companyId()).isEqualTo("co1");
    }

    @Test
    void revokeMakesTokenUnusable() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T00:00:00Z"));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://gw", clock);
        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t1", "r1", "alice", "deepseek", "m", Duration.ofMinutes(5)));
        grants.revoke(lease.token());
        assertThatThrownBy(() -> grants.require("Bearer " + lease.token()))
                .isInstanceOf(ModelRunGrants.UnauthorizedGatewayException.class);
    }

    @Test
    void expiredTokenRejected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T00:00:00Z"));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://gw", clock);
        ModelRunLease lease = TenantContext.call("co1",
                () -> grants.issue("t1", "r1", "alice", "deepseek", "m", Duration.ofMinutes(1)));
        clock.set(Instant.parse("2026-11-24T00:02:00Z"));
        assertThatThrownBy(() -> grants.require(lease.token()))
                .isInstanceOf(ModelRunGrants.UnauthorizedGatewayException.class);
    }

    @Test
    void managedOnlyListedClis() {
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://gw", Clock.systemUTC());
        assertThat(grants.managed("deepseek")).isTrue();
        assertThat(grants.managed("DEEPSEEK")).isTrue();
        assertThat(grants.managed("claude")).isFalse();
    }

    /** Mutable UTC clock for expiry tests. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneOffset zone = ZoneOffset.UTC;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
