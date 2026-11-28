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
                    assertThat(u.runId()).isEqualTo("run-1");
                    assertThat(u.sessionId()).isNull();
                });
        assertThat(ledger.settledRunIds("alice", java.util.List.of("run-1", "other")))
                .containsExactly("run-1");
    }

    @Test
    void settledRunIdsMustNotSilentlyDropPastBatchBoundary() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        int total = JdbcGatewayMetering.SETTLED_RUN_ID_BATCH + 1; // 501
        ledger.putEmployee(period, "alice", total * 2L);
        java.util.List<String> runIds = new java.util.ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            String runId = "run-" + i;
            runIds.add(runId);
            GatewayAttempt a = ledger.reserve(new GatewayMetering.ReserveRequest(
                    "req-" + i, "turn-" + i, runId, "alice", "deepseek", "m", period, 1));
            assertThat(ledger.beginSend(a.id())).isTrue();
            assertThat(ledger.settle(a.id(), 1).status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        }
        assertThat(ledger.settledRunIds("alice", java.util.List.of(runIds.get(total - 1))))
                .containsExactly(runIds.get(total - 1));
        assertThat(ledger.settledRunIds("alice", runIds)).hasSize(total).containsAll(runIds);

        // Two full batches + one: still complete, and an unsettled id stays out.
        int bigger = JdbcGatewayMetering.SETTLED_RUN_ID_BATCH * 2 + 1; // 1001
        ledger.putEmployee(period, "bob", bigger * 2L);
        java.util.List<String> more = new java.util.ArrayList<>(bigger + 1);
        for (int i = 0; i < bigger; i++) {
            String runId = "bob-run-" + i;
            more.add(runId);
            GatewayAttempt a = ledger.reserve(new GatewayMetering.ReserveRequest(
                    "breq-" + i, "bturn-" + i, runId, "bob", "deepseek", "m", period, 1));
            assertThat(ledger.beginSend(a.id())).isTrue();
            assertThat(ledger.settle(a.id(), 1).status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        }
        more.add("never-settled");
        assertThat(ledger.settledRunIds("bob", more)).hasSize(bigger).containsAll(more.subList(0, bigger))
                .doesNotContain("never-settled");
        // Payer boundary: bob's runs must not match alice's query.
        assertThat(ledger.settledRunIds("alice", more)).isEmpty();
    }

    @Test
    void markPendingMustNotOverwriteSettledActual() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 200);
        ledger.putPool(period, GatewayAccountType.RECON_RESERVE, 100);
        GatewayAttempt attempt = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-toctou", "turn-toctou", "run-toctou", "alice", "deepseek", "m", period, 60));
        assertThat(ledger.beginSend(attempt.id())).isTrue();
        GatewayAttempt pending = ledger.markPending(attempt.id(), 70);
        assertThat(pending.status()).isEqualTo(GatewayAttemptStatus.PENDING_RECONCILIATION);
        assertThat(pending.actualTokens()).isEqualTo(70);
        // Settle wins; a late markPending with a different A must not rewrite SETTLED.
        GatewayAttempt settled = ledger.settle(attempt.id(), 65);
        assertThat(settled.status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        assertThat(settled.actualTokens()).isEqualTo(65);
        GatewayAttempt after = ledger.markPending(attempt.id(), 999);
        assertThat(after.status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        assertThat(after.actualTokens()).isEqualTo(65);
    }

    @Test
    void concurrentMarkPendingMustNotOverwriteSettledActual() throws Exception {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 200);
        ledger.putPool(period, GatewayAccountType.RECON_RESERVE, 100);
        GatewayAttempt attempt = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-race", "turn-race", "run-race", "alice", "deepseek", "m", period, 60));
        assertThat(ledger.beginSend(attempt.id())).isTrue();
        ledger.markPending(attempt.id(), 70);

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicReference<GatewayAttempt> settledRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<GatewayAttempt> pendingRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();

        Thread settle = new Thread(() -> TenantContext.run(InCompany.ID, () -> {
            start.countDown();
            try {
                start.await(2, java.util.concurrent.TimeUnit.SECONDS);
                settledRef.set(ledger.settle(attempt.id(), 65));
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        }));
        Thread latePending = new Thread(() -> TenantContext.run(InCompany.ID, () -> {
            start.countDown();
            try {
                start.await(2, java.util.concurrent.TimeUnit.SECONDS);
                // Busy-spin a bit so settle often wins the lock first; either order must end SETTLED@65.
                for (int i = 0; i < 20; i++) {
                    pendingRef.set(ledger.markPending(attempt.id(), 999));
                    if (pendingRef.get().status() == GatewayAttemptStatus.SETTLED) {
                        break;
                    }
                    Thread.yield();
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        }));
        settle.start();
        latePending.start();
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        assertThat(settledRef.get()).isNotNull();
        assertThat(settledRef.get().status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        assertThat(settledRef.get().actualTokens()).isEqualTo(65);
        GatewayAttempt finalState = ledger.markPending(attempt.id(), 1234);
        assertThat(finalState.status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        assertThat(finalState.actualTokens()).isEqualTo(65);
        assertThat(ledger.listProjections(period, 10)).hasSize(1);
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
    void abortUnsentReleasesSendingFreezeAndIsIdempotent() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "bob", 50);
        GatewayAttempt a = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-abort", "t-abort", "r-abort", "bob", "deepseek", "m", period, 20));
        assertThat(ledger.beginSend(a.id())).isTrue();
        ledger.abortUnsent(a.id());
        assertThat(ledger.findEmployee(period, "bob")).hasValueSatisfying(acc -> {
            assertThat(acc.available()).isEqualTo(50);
            assertThat(acc.reserved()).isZero();
        });
        assertThat(ledger.listAttempts(GatewayAttemptStatus.CANCELLED, period, 10))
                .anySatisfy(x -> assertThat(x.id()).isEqualTo(a.id()));
        assertThat(ledger.listAttempts(GatewayAttemptStatus.PENDING_RECONCILIATION, period, 10)).isEmpty();
        // Idempotent replay must not double-credit.
        ledger.abortUnsent(a.id());
        assertThat(ledger.findEmployee(period, "bob")).hasValueSatisfying(acc -> {
            assertThat(acc.available()).isEqualTo(50);
            assertThat(acc.reserved()).isZero();
        });
    }

    @Test
    void abortUnsentMustNotRefundSettledOrPending() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 100);
        GatewayAttempt settled = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-s", "t-s", "r-s", "alice", "deepseek", "m", period, 30));
        assertThat(ledger.beginSend(settled.id())).isTrue();
        assertThat(ledger.settle(settled.id(), 20).status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        ledger.abortUnsent(settled.id());
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(a -> {
            assertThat(a.available()).isEqualTo(80);
            assertThat(a.reserved()).isZero();
            assertThat(a.settled()).isEqualTo(20);
        });

        GatewayAttempt pending = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-p2", "t-p2", "r-p2", "alice", "deepseek", "m", period, 20));
        assertThat(ledger.beginSend(pending.id())).isTrue();
        ledger.markPending(pending.id());
        ledger.abortUnsent(pending.id());
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(a -> {
            assertThat(a.reserved()).isEqualTo(20);
            assertThat(a.settled()).isEqualTo(20);
        });
        assertThat(ledger.listAttempts(GatewayAttemptStatus.PENDING_RECONCILIATION, period, 10))
                .anySatisfy(x -> assertThat(x.id()).isEqualTo(pending.id()));
    }

    @Test
    void abortUnsentIsTenantScoped() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 100);
        GatewayAttempt a = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-ten", "t-ten", "r-ten", "alice", "deepseek", "m", period, 25));
        assertThat(ledger.beginSend(a.id())).isTrue();
        TenantContext.run(InCompany.OTHER, () -> ledger.abortUnsent(a.id()));
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(acc -> {
            assertThat(acc.available()).isEqualTo(75);
            assertThat(acc.reserved()).isEqualTo(25);
        });
        ledger.abortUnsent(a.id());
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(acc -> {
            assertThat(acc.available()).isEqualTo(100);
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
    void fundReconFromUnallocatedWritesLedgerAndNeverMints() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putPool(period, GatewayAccountType.UNALLOCATED, 500_000);
        assertThat(ledger.fundReconReserveToTarget(period, 500_000)).isEqualTo(500_000);
        assertThat(ledger.findAccount(period, GatewayAccountType.RECON_RESERVE, ""))
                .hasValueSatisfying(a -> assertThat(a.available()).isEqualTo(500_000));
        assertThat(ledger.findAccount(period, GatewayAccountType.UNALLOCATED, ""))
                .hasValueSatisfying(a -> assertThat(a.available()).isZero());
        // Paired ledger: UNALLOCATED −move and RECON +move conserve company tokens.
        long fundOut = ledger.listLedger(period, 20).stream()
                .filter(e -> "RECON_FUND_OUT".equals(e.reason()))
                .mapToLong(e -> e.deltaAvailable()).sum();
        long fundIn = ledger.listLedger(period, 20).stream()
                .filter(e -> "RECON_FUND_IN".equals(e.reason()))
                .mapToLong(e -> e.deltaAvailable()).sum();
        assertThat(fundOut).isEqualTo(-500_000);
        assertThat(fundIn).isEqualTo(500_000);
        assertThat(fundOut + fundIn).isZero();

        // Overage drains RECON; a second fund without UNALLOCATED must not mint back to target.
        ledger.putEmployee(period, "alice", 100);
        GatewayAttempt first = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-fund", "turn-fund", "run-fund", "alice", "deepseek", "m", period, 60));
        assertThat(ledger.beginSend(first.id())).isTrue();
        assertThat(ledger.settle(first.id(), 70).status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        assertThat(ledger.findAccount(period, GatewayAccountType.RECON_RESERVE, ""))
                .hasValueSatisfying(a -> assertThat(a.available()).isEqualTo(499_990));

        assertThat(ledger.fundReconReserveToTarget(period, 500_000)).isZero();
        assertThat(ledger.findAccount(period, GatewayAccountType.RECON_RESERVE, ""))
                .hasValueSatisfying(a -> assertThat(a.available()).isEqualTo(499_990));

        // Partial top-up from remaining UNALLOCATED only.
        ledger.putPool(period, GatewayAccountType.UNALLOCATED, 5);
        assertThat(ledger.fundReconReserveToTarget(period, 500_000)).isEqualTo(5);
        assertThat(ledger.findAccount(period, GatewayAccountType.RECON_RESERVE, ""))
                .hasValueSatisfying(a -> assertThat(a.available()).isEqualTo(499_995));
    }

    @Test
    void concurrentReplayMustNotReopenSettledAttempt() throws Exception {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 200);
        ledger.putPool(period, GatewayAccountType.RECON_RESERVE, 10);

        GatewayAttempt attempt = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-conc", "turn-conc", "run-conc", "alice", "deepseek", "m", period, 60));
        assertThat(ledger.beginSend(attempt.id())).isTrue();

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<GatewayAttempt> r1 = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<GatewayAttempt> r2 = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t1 = new Thread(() -> TenantContext.run(InCompany.ID, () -> {
            try {
                start.await();
                r1.set(ledger.settle(attempt.id(), 70));
            } catch (Throwable t) {
                err.compareAndSet(null, t);
            }
        }));
        Thread t2 = new Thread(() -> TenantContext.run(InCompany.ID, () -> {
            try {
                start.await();
                r2.set(ledger.settle(attempt.id(), 70));
            } catch (Throwable t) {
                err.compareAndSet(null, t);
            }
        }));
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();
        assertThat(err.get()).isNull();
        assertThat(r1.get().status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        assertThat(r2.get().status()).isEqualTo(GatewayAttemptStatus.SETTLED);
        assertThat(r1.get().actualTokens()).isEqualTo(70);
        assertThat(r2.get().actualTokens()).isEqualTo(70);
        assertThat(ledger.listAttempts(GatewayAttemptStatus.PENDING_RECONCILIATION, period, 10)).isEmpty();
        assertThat(ledger.listProjections(period, 10)).hasSize(1);
    }

    @Test
    void concurrentDifferentActualRejectsAndKeepsSettled() throws Exception {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 200);
        ledger.putPool(period, GatewayAccountType.RECON_RESERVE, 100);

        GatewayAttempt attempt = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-diff", "turn-diff", "run-diff", "alice", "deepseek", "m", period, 60));
        assertThat(ledger.beginSend(attempt.id())).isTrue();
        GatewayAttempt first = ledger.settle(attempt.id(), 70);
        assertThat(first.status()).isEqualTo(GatewayAttemptStatus.SETTLED);

        assertThatThrownBy(() -> ledger.settle(attempt.id(), 80))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already settled");
        assertThat(ledger.listAttempts(GatewayAttemptStatus.PENDING_RECONCILIATION, period, 10)).isEmpty();
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

    @Test
    void putEmployeePreservesInFlightReserved() {
        JdbcGatewayMetering ledger = newLedger();
        String period = "2026-11";
        ledger.putEmployee(period, "alice", 100);
        GatewayAttempt hold = ledger.reserve(new GatewayMetering.ReserveRequest(
                "req-hold", "turn-hold", "run-hold", "alice", "deepseek", "m", period, 40));
        assertThat(hold.reservedTokens()).isEqualTo(40);
        // Seed/admin upsert must not wipe the freeze (old REPLACE INTO zeroed reserved).
        ledger.putEmployee(period, "alice", 80, 0, 80, 0);
        assertThat(ledger.findEmployee(period, "alice")).hasValueSatisfying(a -> {
            assertThat(a.available()).isEqualTo(80);
            assertThat(a.reserved()).isEqualTo(40);
            assertThat(a.baseQuota()).isEqualTo(80);
        });
        ledger.cancel(hold.id());
    }

    private static JdbcGatewayMetering newLedger() {
        return new JdbcGatewayMetering(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:gw-meter-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
    }
}
