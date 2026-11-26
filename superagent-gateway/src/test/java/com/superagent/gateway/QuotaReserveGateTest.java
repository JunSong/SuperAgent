package com.superagent.gateway;

import com.superagent.core.gateway.GatewayAccount;
import com.superagent.core.gateway.GatewayAccountType;
import com.superagent.core.gateway.GatewayAttempt;
import com.superagent.core.gateway.GatewayAttemptStatus;
import com.superagent.core.gateway.GatewayLedgerEntry;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayQuotaBusyException;
import com.superagent.core.gateway.GatewayQuotaExhaustedException;
import com.superagent.core.gateway.GatewayQuotaInsufficientException;
import com.superagent.core.gateway.GatewayQuotaMode;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** G2-B style: two waiters max; third is immediately QUOTA_BUSY; settle-to-exhaust wakes as EXHAUSTED. */
class QuotaReserveGateTest {

    @Test
    void thirdWaiterIsBusyImmediately() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        HoldMetering ledger = new HoldMetering(100);
        ledger.putEmployee("2026-11", "alice", 5);
        QuotaReserveGate gate = new QuotaReserveGate(clock);
        gate.setTiming(Duration.ofSeconds(2), Duration.ofMillis(20));

        GatewayAttempt hold = TenantContext.call("co1", () -> gate.reserve(ledger, req("h", 5)));
        assertThat(hold.reservedTokens()).isEqualTo(5);

        CountDownLatch entered = new CountDownLatch(2);
        AtomicInteger busyImmediate = new AtomicInteger();
        AtomicInteger waiting = new AtomicInteger();

        Runnable waiter = () -> TenantContext.run("co1", () -> {
            waiting.incrementAndGet();
            entered.countDown();
            try {
                gate.reserve(ledger, req(UUID.randomUUID().toString(), 1));
            } catch (RuntimeException e) {
                // timeout or cancel race — slots are what we assert
            }
        });
        Thread t1 = new Thread(waiter);
        Thread t2 = new Thread(waiter);
        t1.start();
        t2.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        // both must be inside the wait loop (slots taken) before the third arrives
        Thread.sleep(120);

        TenantContext.run("co1", () -> {
            assertThatThrownBy(() -> gate.reserve(ledger, req("third", 1)))
                    .isInstanceOf(GatewayQuotaBusyException.class)
                    .hasMessageContaining("QUOTA_BUSY");
            busyImmediate.incrementAndGet();
        });
        assertThat(busyImmediate.get()).isEqualTo(1);

        ledger.cancel(hold.id());
        t1.join(3000);
        t2.join(3000);
        assertThat(waiting.get()).isEqualTo(2);
    }

    @Test
    void waitsWhenPartialAvailablePlusReservedCouldCoverR() throws Exception {
        Clock clock = Clock.systemUTC();
        HoldMetering ledger = new HoldMetering(200);
        ledger.putEmployee("2026-11", "alice", 100);
        QuotaReserveGate gate = new QuotaReserveGate(clock);
        gate.setTiming(Duration.ofMillis(800), Duration.ofMillis(20));

        GatewayAttempt hold = TenantContext.call("co1", () -> gate.reserve(ledger, req("hold", 50)));
        // available=50, reserved=50; R=60 → mayBecomeAvailable (not only available==0)
        AtomicReference<GatewayAttempt> got = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread waiter = new Thread(() -> TenantContext.run("co1", () -> {
            try {
                got.set(gate.reserve(ledger, req("w", 60)));
            } catch (Throwable t) {
                err.set(t);
            }
        }));
        waiter.start();
        Thread.sleep(40);
        ledger.cancel(hold.id());
        waiter.join(2000);
        assertThat(err.get()).isNull();
        assertThat(got.get()).isNotNull();
        assertThat(got.get().reservedTokens()).isEqualTo(60);
    }

    @Test
    void doesNotWaitWhenEvenFullReleaseCannotCoverR() {
        Clock clock = Clock.systemUTC();
        HoldMetering ledger = new HoldMetering(200);
        ledger.putEmployee("2026-11", "alice", 50);
        QuotaReserveGate gate = new QuotaReserveGate(clock);
        GatewayAttempt hold = TenantContext.call("co1", () -> gate.reserve(ledger, req("hold", 10)));
        // available=40, reserved=10; R=60 → 50 < 60, rethrow INSUFFICIENT (no wait)
        TenantContext.run("co1", () -> assertThatThrownBy(() -> gate.reserve(ledger, req("w", 60)))
                .isInstanceOf(GatewayQuotaInsufficientException.class));
        ledger.cancel(hold.id());
    }

    @Test
    void afterHoldSettlesAtCapWaitersSeeExhausted() throws Exception {
        Clock clock = Clock.systemUTC();
        HoldMetering ledger = new HoldMetering(5);
        ledger.putEmployee("2026-11", "alice", 5);
        QuotaReserveGate gate = new QuotaReserveGate(clock);
        gate.setTiming(Duration.ofMillis(800), Duration.ofMillis(20));

        GatewayAttempt hold = TenantContext.call("co1", () -> gate.reserve(ledger, req("hold", 5)));
        ledger.beginSend(hold.id());

        AtomicReference<Throwable> seen = new AtomicReference<>();
        Thread waiter = new Thread(() -> TenantContext.run("co1", () -> {
            try {
                gate.reserve(ledger, req("w", 1));
            } catch (Throwable t) {
                seen.set(t);
            }
        }));
        waiter.start();
        Thread.sleep(40);
        ledger.settle(hold.id(), 5); // available stays 0, used = B
        waiter.join(2000);
        assertThat(seen.get()).isInstanceOf(GatewayQuotaExhaustedException.class);
    }

    private static GatewayMetering.ReserveRequest req(String id, long r) {
        return new GatewayMetering.ReserveRequest(id, "t-" + id, "r-" + id, "alice", "deepseek", "m", "2026-11", r);
    }

    /** In-memory ledger with a hard baseQuota for exhausted-after-settle. */
    static final class HoldMetering implements GatewayMetering {
        private final long baseQuota;
        private final Map<String, AtomicLong> available = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> reserved = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> settled = new ConcurrentHashMap<>();
        private final Map<String, GatewayAttempt> attempts = new ConcurrentHashMap<>();

        HoldMetering(long baseQuota) {
            this.baseQuota = baseQuota;
        }

        private static String key(String period, String user) {
            return period + "|" + user;
        }

        @Override
        public void putEmployee(String period, String userId, long avail) {
            available.put(key(period, userId), new AtomicLong(avail));
            reserved.put(key(period, userId), new AtomicLong(0));
            settled.put(key(period, userId), new AtomicLong(0));
        }

        @Override
        public void putEmployee(String period, String userId, long avail, long settledTokens, long base, long f) {
            putEmployee(period, userId, avail);
            settled.put(key(period, userId), new AtomicLong(settledTokens));
        }

        @Override
        public void putPool(String period, GatewayAccountType type, long avail) {
        }

        @Override
        public Optional<GatewayAccount> findEmployee(String period, String userId) {
            return findAccount(period, GatewayAccountType.EMPLOYEE, userId);
        }

        @Override
        public Optional<GatewayAccount> findAccount(String period, GatewayAccountType type, String ownerId) {
            AtomicLong a = available.get(key(period, ownerId));
            if (a == null) {
                return Optional.empty();
            }
            return Optional.of(new GatewayAccount(period, type, ownerId, GatewayQuotaMode.LIMITED,
                    a.get(), reserved.get(key(period, ownerId)).get(), settled.get(key(period, ownerId)).get(),
                    baseQuota, 0, null));
        }

        @Override
        public GatewayAttempt reserve(ReserveRequest request) {
            String k = key(request.period(), request.payerUserId());
            long used = settled.get(k).get() + reserved.get(k).get();
            if (used >= baseQuota) {
                throw new GatewayQuotaExhaustedException("QUOTA_EXHAUSTED");
            }
            AtomicLong avail = available.get(k);
            if (avail.get() < request.reservedTokens()) {
                throw new GatewayQuotaInsufficientException("QUOTA_INSUFFICIENT");
            }
            avail.addAndGet(-request.reservedTokens());
            reserved.get(k).addAndGet(request.reservedTokens());
            String id = UUID.randomUUID().toString();
            GatewayAttempt attempt = new GatewayAttempt(id, request.requestId(), 1, request.turnId(), request.runId(),
                    request.payerUserId(), request.cli(), request.model(), request.period(),
                    request.reservedTokens(), 0, GatewayAttemptStatus.RESERVED);
            attempts.put(id, attempt);
            return attempt;
        }

        @Override
        public boolean beginSend(String attemptId) {
            GatewayAttempt a = attempts.get(attemptId);
            attempts.put(attemptId, new GatewayAttempt(a.id(), a.requestId(), a.attemptNo(), a.turnId(), a.runId(),
                    a.payerUserId(), a.cli(), a.model(), a.period(), a.reservedTokens(), a.actualTokens(),
                    GatewayAttemptStatus.SENDING));
            return true;
        }

        @Override
        public GatewayAttempt settle(String attemptId, long actualTokens) {
            GatewayAttempt a = attempts.get(attemptId);
            long r = a.reservedTokens();
            long spent = Math.min(actualTokens, r);
            long refund = r - spent;
            String k = key(a.period(), a.payerUserId());
            available.get(k).addAndGet(refund);
            reserved.get(k).addAndGet(-r);
            settled.get(k).addAndGet(spent);
            GatewayAttempt done = new GatewayAttempt(a.id(), a.requestId(), a.attemptNo(), a.turnId(), a.runId(),
                    a.payerUserId(), a.cli(), a.model(), a.period(), r, spent, GatewayAttemptStatus.SETTLED);
            attempts.put(attemptId, done);
            return done;
        }

        @Override
        public GatewayAttempt markPending(String attemptId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(String attemptId) {
            GatewayAttempt a = attempts.remove(attemptId);
            if (a == null) {
                return;
            }
            String k = key(a.period(), a.payerUserId());
            available.get(k).addAndGet(a.reservedTokens());
            reserved.get(k).addAndGet(-a.reservedTokens());
        }

        @Override
        public GatewayAccount allocate(String operationId, String period, String toUserId, long amount, String actorUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GatewayAccount setFloatAllowance(String period, String userId, long floatAllowance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int applyDefaultFloat(String period, long floatAllowance) {
            return 0;
        }

        @Override
        public GatewayAccount reclaim(String operationId, String period, String fromUserId, long amount, String actorUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void transfer(String operationId, String period, String fromUserId, String toUserId, long amount,
                             String actorUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GatewayLedgerEntry> listLedger(String period, int limit) {
            return List.of();
        }
    }
}
