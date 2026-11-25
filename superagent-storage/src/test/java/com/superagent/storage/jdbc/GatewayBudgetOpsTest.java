package com.superagent.storage.jdbc;

import com.superagent.core.gateway.GatewayAccountType;
import com.superagent.core.gateway.GatewayConflictException;
import com.superagent.core.gateway.GatewayMetering;
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
 * PR-05 acceptance (#34): available 100 with 40 frozen → transfer 101 refused, 70 moves;
 * allocate/reclaim; idempotent operation_id; cross-company isolated.
 */
@ExtendWith(InCompany.class)
class GatewayBudgetOpsTest {

    private static final String PERIOD = "2026-11";

    @Test
    void transferWithFreezeMatchesIssueAcceptance() {
        JdbcGatewayMetering ledger = newLedger();
        ledger.putPool(PERIOD, GatewayAccountType.UNALLOCATED, 0);
        // Total 140: freeze 40 → available 100, reserved 40
        ledger.putEmployee(PERIOD, "alice", 140, 0, 140, 0);
        ledger.putEmployee(PERIOD, "bob", 0, 0, 0, 0);
        var hold = ledger.reserve(new GatewayMetering.ReserveRequest(
                "hold", "t", "r", "alice", "deepseek", "m", PERIOD, 40));
        assertThat(ledger.findEmployee(PERIOD, "alice")).hasValueSatisfying(a -> {
            assertThat(a.available()).isEqualTo(100);
            assertThat(a.reserved()).isEqualTo(40);
        });

        assertThatThrownBy(() -> ledger.transfer("tx-101", PERIOD, "alice", "bob", 101, "admin"))
                .isInstanceOf(GatewayQuotaInsufficientException.class);

        ledger.transfer("tx-70", PERIOD, "alice", "bob", 70, "admin");
        assertThat(ledger.findEmployee(PERIOD, "alice")).hasValueSatisfying(a -> {
            assertThat(a.available()).isEqualTo(30);
            assertThat(a.reserved()).isEqualTo(40);
        });
        assertThat(ledger.findEmployee(PERIOD, "bob")).hasValueSatisfying(a -> assertThat(a.available()).isEqualTo(70));

        // Idempotent replay — balances unchanged
        ledger.transfer("tx-70", PERIOD, "alice", "bob", 70, "admin");
        assertThat(ledger.findEmployee(PERIOD, "alice")).hasValueSatisfying(a -> assertThat(a.available()).isEqualTo(30));
        assertThat(ledger.findEmployee(PERIOD, "bob")).hasValueSatisfying(a -> assertThat(a.available()).isEqualTo(70));

        assertThatThrownBy(() -> ledger.transfer("tx-70", PERIOD, "alice", "bob", 71, "admin"))
                .isInstanceOf(GatewayConflictException.class);

        ledger.cancel(hold.id());
    }

    @Test
    void allocateReclaimAndCrossCompanyIsolated() {
        JdbcGatewayMetering ledger = newLedger();
        ledger.putPool(PERIOD, GatewayAccountType.UNALLOCATED, 80);
        ledger.allocate("a1", PERIOD, "carol", 50, "admin");
        assertThat(ledger.findEmployee(PERIOD, "carol")).hasValueSatisfying(a -> {
            assertThat(a.available()).isEqualTo(50);
            assertThat(a.baseQuota()).isEqualTo(50);
        });
        assertThat(ledger.findAccount(PERIOD, GatewayAccountType.UNALLOCATED, ""))
                .hasValueSatisfying(p -> assertThat(p.available()).isEqualTo(30));

        ledger.allocate("a1", PERIOD, "carol", 50, "admin"); // replay
        assertThat(ledger.findEmployee(PERIOD, "carol")).hasValueSatisfying(a -> assertThat(a.available()).isEqualTo(50));

        ledger.reclaim("rc1", PERIOD, "carol", 20, "admin");
        assertThat(ledger.findEmployee(PERIOD, "carol")).hasValueSatisfying(a -> assertThat(a.available()).isEqualTo(30));
        assertThat(ledger.findAccount(PERIOD, GatewayAccountType.UNALLOCATED, ""))
                .hasValueSatisfying(p -> assertThat(p.available()).isEqualTo(50));

        ledger.allocate("a2", PERIOD, "dave", 10, "admin");
        assertThat(ledger.listEmployees(PERIOD)).extracting(a -> a.owner())
                .containsExactly("carol", "dave");

        assertThat(ledger.listLedger(PERIOD, 50)).isNotEmpty();

        TenantContext.run(InCompany.OTHER, () ->
                assertThat(ledger.findEmployee(PERIOD, "carol")).isEmpty());
    }

    @Test
    void setAndApplyDefaultFloat() {
        JdbcGatewayMetering ledger = newLedger();
        ledger.putPool(PERIOD, GatewayAccountType.UNALLOCATED, 100);
        ledger.allocate("f1", PERIOD, "erin", 40, "admin");
        ledger.allocate("f2", PERIOD, "frank", 40, "admin");
        assertThat(ledger.findEmployee(PERIOD, "erin")).hasValueSatisfying(a -> assertThat(a.floatAllowance()).isZero());

        ledger.setFloatAllowance(PERIOD, "erin", 100_000);
        assertThat(ledger.findEmployee(PERIOD, "erin")).hasValueSatisfying(a ->
                assertThat(a.floatAllowance()).isEqualTo(100_000));

        assertThat(ledger.applyDefaultFloat(PERIOD, 100_000)).isEqualTo(1); // frank only
        assertThat(ledger.findEmployee(PERIOD, "frank")).hasValueSatisfying(a ->
                assertThat(a.floatAllowance()).isEqualTo(100_000));
        assertThat(ledger.applyDefaultFloat(PERIOD, 100_000)).isZero();
    }

    private static JdbcGatewayMetering newLedger() {
        return new JdbcGatewayMetering(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:gw-budget-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
    }
}
