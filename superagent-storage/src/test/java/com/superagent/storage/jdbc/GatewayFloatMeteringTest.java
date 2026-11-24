package com.superagent.storage.jdbc;

import com.superagent.core.gateway.GatewayAttempt;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayQuotaExhaustedException;
import com.superagent.core.gateway.GatewayQuotaInsufficientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-04 acceptance (#34): B=1_000_000, F=100_000; float binds one turn;
 * past B only that turn continues; B+F stops; two windows cannot both claim F.
 */
@ExtendWith(InCompany.class)
class GatewayFloatMeteringTest {

    private static final long B = 1_000_000L;
    private static final long F = 100_000L;
    private static final String PERIOD = "2026-11";

    @Test
    void floatTurnContinuesPastBaseWhileNewTurnIsExhausted() {
        JdbcGatewayMetering ledger = newLedger();
        // 980k already spent, 20k left under B
        ledger.putEmployee(PERIOD, "alice", 20_000, 980_000, B, F);
        ledger.putPool(PERIOD, com.superagent.core.gateway.GatewayAccountType.FLOAT_POOL, F);

        GatewayAttempt a1 = ledger.reserve(req("r1", "turn-A", 10_000));
        ledger.beginSend(a1.id());
        ledger.settle(a1.id(), 10_000); // settled = 990k, available = 10k

        assertThat(ledger.findEmployee(PERIOD, "alice")).hasValueSatisfying(acc -> {
            assertThat(acc.settled()).isEqualTo(990_000);
            assertThat(acc.available()).isEqualTo(10_000);
        });

        // Same turn crosses B → claims float
        GatewayAttempt a2 = ledger.reserve(req("r2", "turn-A", 40_000));
        assertThat(ledger.findEmployee(PERIOD, "alice")).hasValueSatisfying(acc -> {
            assertThat(acc.floatTurnId()).isEqualTo("turn-A");
            assertThat(acc.available()).isEqualTo(10_000 + F - 40_000);
        });
        ledger.beginSend(a2.id());
        ledger.settle(a2.id(), 40_000); // settled = 1_030_000

        assertThatThrownBy(() -> ledger.reserve(req("r3", "turn-B", 1_000)))
                .isInstanceOf(GatewayQuotaExhaustedException.class)
                .hasMessageContaining("QUOTA_EXHAUSTED");

        // Float turn can still spend until B+F
        GatewayAttempt a3 = ledger.reserve(req("r4", "turn-A", 50_000));
        ledger.beginSend(a3.id());
        ledger.settle(a3.id(), 50_000); // settled = 1_080_000

        assertThatThrownBy(() -> ledger.reserve(req("r5", "turn-A", 30_000)))
                .isInstanceOf(GatewayQuotaInsufficientException.class); // would exceed B+F=1.1M
    }

    @Test
    void onlyOneWindowClaimsFloat() throws Exception {
        JdbcGatewayMetering ledger = newLedger();
        ledger.putEmployee(PERIOD, "alice", 5_000, 995_000, B, F);
        ledger.putPool(PERIOD, com.superagent.core.gateway.GatewayAccountType.FLOAT_POOL, F);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String turn : List.of("win-1", "win-2")) {
                futures.add(pool.submit(() -> {
                    com.superagent.core.tenant.TenantContext.run(InCompany.ID, () -> {
                        ready.countDown();
                        try {
                            assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        try {
                            GatewayAttempt a = ledger.reserve(req("req-" + turn, turn, 10_000));
                            wins.incrementAndGet();
                            ledger.cancel(a.id());
                        } catch (GatewayQuotaExhaustedException e) {
                            exhausted.incrementAndGet();
                        }
                    });
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(wins.get()).isEqualTo(1);
        assertThat(exhausted.get()).isEqualTo(1);
        assertThat(ledger.findEmployee(PERIOD, "alice")).hasValueSatisfying(acc ->
                assertThat(acc.floatTurnId()).isIn("win-1", "win-2"));
    }

    private static GatewayMetering.ReserveRequest req(String requestId, String turnId, long r) {
        return new GatewayMetering.ReserveRequest(requestId, turnId, "run-" + requestId, "alice",
                "deepseek", "deepseek-chat", PERIOD, r);
    }

    private static JdbcGatewayMetering newLedger() {
        return new JdbcGatewayMetering(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:gw-float-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
    }
}
