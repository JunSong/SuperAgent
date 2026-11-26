package com.superagent.storage.jdbc;

import com.superagent.core.gateway.GatewayAccountType;
import com.superagent.core.gateway.GatewayAttempt;
import com.superagent.core.gateway.GatewayAttemptStatus;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayQuotaExhaustedException;
import com.superagent.core.gateway.GatewayQuotaInsufficientException;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-03 acceptance (#34): balance 100, first freezes 60 → second R=60 rejected;
 * settle A=35 → available 65; replay settle ten times still 65.
 */
@ExtendWith(InCompany.class)
class GatewayMeteringTest {

    @Test
    void reserveBlocksSecondWhenBalanceHeldThenSettleIsIdempotent() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 100);

        GatewayAttempt first = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-1", "turn-1", "run-1", "alice", "deepseek", "deepseek-chat", period, 60));
        assertThat(first.status()).isEqualTo(GatewayAttemptStatus.RESERVED);
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(a -> {
            assertThat(a.available()).isEqualTo(40);
            assertThat(a.reserved()).isEqualTo(60);
            assertThat(a.settled()).isZero();
        });

        assertThatThrownBy(() -> ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-2", "turn-2", "run-2", "alice", "deepseek", "deepseek-chat", period, 60)))
                .isInstanceOf(GatewayQuotaInsufficientException.class)
                .hasMessageContaining("QUOTA_INSUFFICIENT");

        assertThat(ledger.beginSend(first.id())).isTrue();
        GatewayAttempt settled = ledger.settle(first.id(), 35);
        assertThat(settled.status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        assertThat(settled.actualTokens()).isEqualTo(35);
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(a -> {
            assertThat(a.available()).isEqualTo(65);
            assertThat(a.reserved()).isZero();
            assertThat(a.settled()).isEqualTo(35);
        });

        for (int i = 0; i < 10; i++) {
            GatewayAttempt again = ledger.settle(first.id(), 35);
            assertThat(again.actualTokens()).isEqualTo(35);
            assertThat(again.status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        }
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(a -> {
            assertThat(a.available()).isEqualTo(65);
            assertThat(a.reserved()).isZero();
            assertThat(a.settled()).isEqualTo(35);
        });
        assertThat(ledger.listProjections(period, 10)).hasSize(1)
                .first().satisfies(p -> {
                    assertThat(p.attemptId()).isEqualTo(first.id());
                    assertThat(p.actualTokens()).isEqualTo(35);
                    assertThat(p.eventType()).isEqualTo("settlement");
                });
        assertThat(ledger.between("alice", java.time.Instant.EPOCH, java.time.Instant.now().plusSeconds(60)))
                .singleElement().satisfies(u -> {
                    assertThat(u.source()).isEqualTo(com.superagent.core.usage.UsageContext.SOURCE_GATEWAY);
                    assertThat(u.total()).isEqualTo(35);
                    assertThat(u.user()).isEqualTo("alice");
                });
    }

    @Test
    void pendingReconcileSettlesAndProjectsOnce() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 100);
        GatewayAttempt a = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-p", "turn-p", "run-p", "alice", "deepseek", "m", period, 40));
        assertThat(ledger.beginSend(a.id())).isTrue();
        ledger.markPending(a.id());
        assertThat(ledger.listAttempts(GatewayAttemptStatus.PENDING_RECONCILIATION, period, 10)).hasSize(1);

        GatewayAttempt settled = ledger.settle(a.id(), 22);
        assertThat(settled.status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        assertThat(settled.actualTokens()).isEqualTo(22);
        assertThat(ledger.listAttempts(GatewayAttemptStatus.PENDING_RECONCILIATION, period, 10)).isEmpty();
        assertThat(ledger.listProjections(period, 10)).hasSize(1);
    }

    @Test
    void cancelReleasesReservedBeforeSend() {
        JdbcGatewayMetering ledger = newLedger();
        ledger.putEmployee("2026-11", "bob", 50);
        GatewayAttempt a = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-c", "t", "r", "bob", "deepseek", "m", "2026-11", 20));
        ledger.cancel(a.id());
        assertThat(ledger.findEmployee("2026-11", "bob")).hasValueSatisfying(acc -> {
            assertThat(acc.available()).isEqualTo(50);
            assertThat(acc.reserved()).isZero();
        });
    }

    @Test
    void otherCompanySeesNone() {
        JdbcGatewayMetering ledger = newLedger();
        ledger.putEmployee("2026-11", "alice", 100);
        TenantContext.run(InCompany.OTHER, () ->
                assertThat(ledger.findEmployee("2026-11", "alice")).isEmpty());
    }

    @Test
    void actualUsageMustNotBeTruncatedToReservation() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 100);
        ledger.putPool(period, com.superagent.core.gateway.GatewayAccountType.RECON_RESERVE, 1_000);

        GatewayAttempt first = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-a70", "turn-a", "run-a", "alice", "deepseek", "m", period, 60));
        assertThat(ledger.beginSend(first.id())).isTrue();
        GatewayAttempt settled = ledger.settle(first.id(), 70);
        assertThat(settled.actualTokens()).isEqualTo(70);
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(a -> {
            assertThat(a.available()).isEqualTo(40); // 100 - 60 employee charge
            assertThat(a.reserved()).isZero();
            assertThat(a.settled()).isEqualTo(60);
        });
        assertThat(ledger.findAccount(period, com.superagent.core.gateway.GatewayAccountType.RECON_RESERVE, ""))
                .hasValueSatisfying(p -> assertThat(p.available()).isEqualTo(990));
        assertThat(ledger.listProjections(period, 10)).singleElement()
                .satisfies(p -> assertThat(p.actualTokens()).isEqualTo(70));

        // Idempotent replay of the same full A
        assertThat(ledger.settle(first.id(), 70).actualTokens()).isEqualTo(70);
        assertThat(ledger.findAccount(period, com.superagent.core.gateway.GatewayAccountType.RECON_RESERVE, ""))
                .hasValueSatisfying(p -> assertThat(p.available()).isEqualTo(990));
    }

    @Test
    void overageWithoutReconReserveKeepsKnownActualPending() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 100);
        // No RECON_RESERVE funding — settle A>R must still store A.
        GatewayAttempt first = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-pend", "turn-p", "run-p", "alice", "deepseek", "m", period, 60));
        assertThat(ledger.beginSend(first.id())).isTrue();
        GatewayAttempt pending = ledger.settle(first.id(), 70);
        assertThat(pending.status()).isEqualTo(GatewayAttemptStatus.PENDING_RECONCILIATION);
        assertThat(pending.actualTokens()).isEqualTo(70);
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(a -> {
            assertThat(a.reserved()).isEqualTo(60); // freeze held until reconcile funds RECON
            assertThat(a.settled()).isZero();
        });
    }

    @Test
    void concurrentOverageDoesNotStealSiblingFreeze() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 200);
        ledger.putPool(period, com.superagent.core.gateway.GatewayAccountType.RECON_RESERVE, 10); // covers one overage of 10

        GatewayAttempt a = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-a", "ta", "ra", "alice", "deepseek", "m", period, 60));
        GatewayAttempt b = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-b", "tb", "rb", "alice", "deepseek", "m", period, 60));
        assertThat(ledger.beginSend(a.id())).isTrue();
        assertThat(ledger.beginSend(b.id())).isTrue();
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(acc ->
                assertThat(acc.reserved()).isEqualTo(120));

        GatewayAttempt settledA = ledger.settle(a.id(), 70); // needs overage 10 — takes the pool
        assertThat(settledA.status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        GatewayAttempt pendingB = ledger.settle(b.id(), 70); // RECON empty — must keep its own freeze
        assertThat(pendingB.status()).isEqualTo(GatewayAttemptStatus.PENDING_RECONCILIATION);
        assertThat(pendingB.actualTokens()).isEqualTo(70);
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(acc -> {
            assertThat(acc.reserved()).isEqualTo(60); // B's freeze intact; A released
            assertThat(acc.settled()).isEqualTo(60);
        });

        // Retry B without funding RECON — must not consume the remaining freeze again.
        GatewayAttempt stillPending = ledger.settle(b.id(), 70);
        assertThat(stillPending.status()).isEqualTo(GatewayAttemptStatus.PENDING_RECONCILIATION);
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(acc ->
                assertThat(acc.reserved()).isEqualTo(60));
    }

    @Test
    void cancelledAttemptMustNotUnlockFloatForSameTurnId() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 100, 0, 100, 50);
        ledger.putPool(period, GatewayAccountType.FLOAT_POOL, 50);

        GatewayAttempt cancelled = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-old", "turn-stale", "run-1", "alice", "deepseek", "m", period, 60));
        ledger.cancel(cancelled.id());

        GatewayAttempt deplete = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-dep", "turn-dep", "run-2", "alice", "deepseek", "m", period, 100));
        assertThat(ledger.beginSend(deplete.id())).isTrue();
        ledger.settle(deplete.id(), 100);

        assertThatThrownBy(() -> ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-reuse", "turn-stale", "run-3", "alice", "deepseek", "m", period, 10)))
                .isInstanceOf(GatewayQuotaExhaustedException.class)
                .hasMessageContaining("QUOTA_EXHAUSTED");
    }

    @Test
    void settledAttemptAllowsSameTurnFloatContinuation() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 100, 0, 100, 50);
        ledger.putPool(period, GatewayAccountType.FLOAT_POOL, 50);

        GatewayAttempt first = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-s1", "turn-live", "run-s", "alice", "deepseek", "m", period, 100));
        assertThat(ledger.beginSend(first.id())).isTrue();
        ledger.settle(first.id(), 100);

        GatewayAttempt cont = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-s2", "turn-live", "run-s", "alice", "deepseek", "m", period, 10));
        assertThat(cont.status()).isEqualTo(GatewayAttemptStatus.RESERVED);
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(a -> {
            assertThat(a.floatTurnId()).isEqualTo("turn-live");
            assertThat(a.reserved()).isEqualTo(10);
        });
    }

    private static JdbcGatewayMetering newLedger() {
        return new JdbcGatewayMetering(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:gw-meter-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
    }
}
