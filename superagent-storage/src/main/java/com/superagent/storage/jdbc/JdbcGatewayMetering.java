package com.superagent.storage.jdbc;

import com.superagent.core.gateway.GatewayAccount;
import com.superagent.core.gateway.GatewayAccountType;
import com.superagent.core.gateway.GatewayAttempt;
import com.superagent.core.gateway.GatewayAttemptStatus;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayQuotaInsufficientException;
import com.superagent.core.gateway.GatewayQuotaMode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MySQL/H2 ledger for #34 PR-03: atomic reserve / beginSend / settle with F=0.
 * Tables: {@code gateway_account}, {@code gateway_attempt}, {@code gateway_ledger}.
 */
public class JdbcGatewayMetering implements GatewayMetering {

    public static final String ACCOUNT_DDL = """
            CREATE TABLE IF NOT EXISTS gateway_account (
              company_id        VARCHAR(64)  NOT NULL,
              period            VARCHAR(7)   NOT NULL,
              account_type      VARCHAR(32)  NOT NULL,
              owner_id          VARCHAR(128) NOT NULL,
              quota_mode        VARCHAR(16)  NOT NULL,
              available_tokens  BIGINT       NOT NULL,
              reserved_tokens   BIGINT       NOT NULL,
              settled_tokens    BIGINT       NOT NULL,
              PRIMARY KEY (company_id, period, account_type, owner_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    public static final String ATTEMPT_DDL = """
            CREATE TABLE IF NOT EXISTS gateway_attempt (
              company_id       VARCHAR(64)  NOT NULL,
              id               VARCHAR(64)  NOT NULL,
              request_id       VARCHAR(128) NOT NULL,
              attempt_no       INT          NOT NULL,
              turn_id          VARCHAR(128) NOT NULL,
              run_id           VARCHAR(128) NOT NULL,
              payer_user_id    VARCHAR(128) NOT NULL,
              cli              VARCHAR(64)  NOT NULL,
              model            VARCHAR(128) NOT NULL,
              period           VARCHAR(7)   NOT NULL,
              reserved_tokens  BIGINT       NOT NULL,
              actual_tokens    BIGINT       NOT NULL,
              status           VARCHAR(32)  NOT NULL,
              PRIMARY KEY (company_id, id),
              UNIQUE KEY uk_gateway_attempt_request (company_id, request_id, attempt_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    public static final String LEDGER_DDL = """
            CREATE TABLE IF NOT EXISTS gateway_ledger (
              company_id    VARCHAR(64)  NOT NULL,
              operation_id  VARCHAR(128) NOT NULL,
              entry_no      INT          NOT NULL,
              period        VARCHAR(7)   NOT NULL,
              account_type  VARCHAR(32)  NOT NULL,
              owner_id      VARCHAR(128) NOT NULL,
              delta_available BIGINT     NOT NULL,
              delta_reserved  BIGINT     NOT NULL,
              delta_settled   BIGINT     NOT NULL,
              reason        VARCHAR(64)  NOT NULL,
              attempt_id    VARCHAR(64)  NULL,
              PRIMARY KEY (company_id, operation_id, entry_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private static final RowMapper<GatewayAccount> ACCOUNT = (rs, n) -> new GatewayAccount(
            rs.getString("period"),
            GatewayAccountType.valueOf(rs.getString("account_type")),
            rs.getString("owner_id"),
            GatewayQuotaMode.valueOf(rs.getString("quota_mode")),
            rs.getLong("available_tokens"),
            rs.getLong("reserved_tokens"),
            rs.getLong("settled_tokens"));

    private static final RowMapper<GatewayAttempt> ATTEMPT = (rs, n) -> new GatewayAttempt(
            rs.getString("id"),
            rs.getString("request_id"),
            rs.getInt("attempt_no"),
            rs.getString("turn_id"),
            rs.getString("run_id"),
            rs.getString("payer_user_id"),
            rs.getString("cli"),
            rs.getString("model"),
            rs.getString("period"),
            rs.getLong("reserved_tokens"),
            rs.getLong("actual_tokens"),
            GatewayAttemptStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcGatewayMetering(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "JdbcTemplate needs a DataSource")));
        TenantSchema.create(jdbc, ACCOUNT_DDL);
        TenantSchema.create(jdbc, ATTEMPT_DDL);
        TenantSchema.create(jdbc, LEDGER_DDL);
    }

    @Override
    public void putEmployee(String period, String userId, long available) {
        if (period == null || period.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("period and userId are required");
        }
        if (available < 0) {
            throw new IllegalArgumentException("available must be >= 0");
        }
        String company = TenantSchema.company();
        // REPLACE keeps H2 (MODE=MySQL) and MySQL aligned for upsert-by-primary-key.
        jdbc.update("""
                REPLACE INTO gateway_account (company_id, period, account_type, owner_id, quota_mode,
                  available_tokens, reserved_tokens, settled_tokens)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0)
                """,
                company, period, GatewayAccountType.EMPLOYEE.name(), userId,
                GatewayQuotaMode.LIMITED.name(), available);
    }

    @Override
    public Optional<GatewayAccount> findEmployee(String period, String userId) {
        return jdbc.query("""
                        SELECT period, account_type, owner_id, quota_mode, available_tokens, reserved_tokens, settled_tokens
                        FROM gateway_account
                        WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                        """,
                ACCOUNT, TenantSchema.company(), period, GatewayAccountType.EMPLOYEE.name(), userId)
                .stream().findFirst();
    }

    @Override
    public GatewayAttempt reserve(ReserveRequest request) {
        validateReserve(request);
        return tx.execute(status -> {
            String company = TenantSchema.company();
            GatewayAccount account = findEmployee(request.period(), request.payerUserId())
                    .orElseThrow(() -> new GatewayQuotaInsufficientException(
                            "员工本月尚未分配模型额度（period=" + request.period() + "）"));
            if (account.quotaMode() == GatewayQuotaMode.UNLIMITED) {
                throw new IllegalStateException("UNLIMITED accounts do not reserve; skip metering at the caller");
            }
            long r = request.reservedTokens();
            int updated = jdbc.update("""
                    UPDATE gateway_account
                    SET available_tokens = available_tokens - ?, reserved_tokens = reserved_tokens + ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                      AND quota_mode = ? AND available_tokens >= ?
                    """,
                    r, r, company, request.period(), GatewayAccountType.EMPLOYEE.name(), request.payerUserId(),
                    GatewayQuotaMode.LIMITED.name(), r);
            if (updated == 0) {
                throw new GatewayQuotaInsufficientException(
                        "可用额度不足：需要预占 " + r + " tokens（QUOTA_INSUFFICIENT）");
            }
            String attemptId = UUID.randomUUID().toString().replace("-", "");
            try {
                jdbc.update("""
                        INSERT INTO gateway_attempt (company_id, id, request_id, attempt_no, turn_id, run_id,
                          payer_user_id, cli, model, period, reserved_tokens, actual_tokens, status)
                        VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                        """,
                        company, attemptId, request.requestId(), request.turnId(), request.runId(),
                        request.payerUserId(), request.cli(), nullToEmpty(request.model()), request.period(),
                        r, GatewayAttemptStatus.RESERVED.name());
            } catch (DuplicateKeyException e) {
                status.setRollbackOnly();
                throw new IllegalStateException("duplicate gateway request_id: " + request.requestId(), e);
            }
            writeLedger(company, "reserve:" + attemptId, 1, request.period(), request.payerUserId(),
                    -r, r, 0, "RESERVE", attemptId);
            return requireAttempt(company, attemptId);
        });
    }

    @Override
    public boolean beginSend(String attemptId) {
        if (attemptId == null || attemptId.isBlank()) {
            return false;
        }
        Boolean ok = tx.execute(status -> {
            int n = jdbc.update("""
                    UPDATE gateway_attempt SET status = ?
                    WHERE company_id = ? AND id = ? AND status = ?
                    """,
                    GatewayAttemptStatus.SENDING.name(), TenantSchema.company(), attemptId,
                    GatewayAttemptStatus.RESERVED.name());
            return n == 1;
        });
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public GatewayAttempt settle(String attemptId, long actualTokens) {
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("attemptId is required");
        }
        if (actualTokens < 0) {
            throw new IllegalArgumentException("actualTokens must be >= 0");
        }
        return tx.execute(status -> {
            String company = TenantSchema.company();
            GatewayAttempt current = requireAttempt(company, attemptId);
            if (current.status() == GatewayAttemptStatus.SETTLED) {
                // Idempotent replay: same A succeeds; different A is a conflict.
                if (current.actualTokens() == actualTokens) {
                    return current;
                }
                throw new IllegalStateException("attempt already settled with A=" + current.actualTokens());
            }
            if (current.status() != GatewayAttemptStatus.SENDING
                    && current.status() != GatewayAttemptStatus.PENDING_RECONCILIATION
                    && current.status() != GatewayAttemptStatus.RESERVED) {
                throw new IllegalStateException("cannot settle attempt in status " + current.status());
            }
            long r = current.reservedTokens();
            long a = Math.min(actualTokens, r); // PR-03: A>R kept for later recon; settle within R
            long refund = r - a;
            int moved = jdbc.update("""
                    UPDATE gateway_account
                    SET available_tokens = available_tokens + ?,
                        reserved_tokens = reserved_tokens - ?,
                        settled_tokens = settled_tokens + ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                      AND reserved_tokens >= ?
                    """,
                    refund, r, a, company, current.period(), GatewayAccountType.EMPLOYEE.name(),
                    current.payerUserId(), r);
            if (moved == 0) {
                throw new IllegalStateException("account reserved balance missing for attempt " + attemptId);
            }
            jdbc.update("""
                    UPDATE gateway_attempt SET status = ?, actual_tokens = ?
                    WHERE company_id = ? AND id = ?
                    """,
                    GatewayAttemptStatus.SETTLED.name(), a, company, attemptId);
            writeLedger(company, "settle:" + attemptId, 1, current.period(), current.payerUserId(),
                    refund, -r, a, "SETTLE", attemptId);
            return requireAttempt(company, attemptId);
        });
    }

    @Override
    public GatewayAttempt markPending(String attemptId) {
        return tx.execute(status -> {
            String company = TenantSchema.company();
            GatewayAttempt current = requireAttempt(company, attemptId);
            if (current.status() == GatewayAttemptStatus.SETTLED
                    || current.status() == GatewayAttemptStatus.PENDING_RECONCILIATION) {
                return current;
            }
            jdbc.update("""
                    UPDATE gateway_attempt SET status = ?
                    WHERE company_id = ? AND id = ? AND status IN (?, ?)
                    """,
                    GatewayAttemptStatus.PENDING_RECONCILIATION.name(), company, attemptId,
                    GatewayAttemptStatus.SENDING.name(), GatewayAttemptStatus.RESERVED.name());
            return requireAttempt(company, attemptId);
        });
    }

    @Override
    public void cancel(String attemptId) {
        if (attemptId == null || attemptId.isBlank()) {
            return;
        }
        tx.executeWithoutResult(status -> {
            String company = TenantSchema.company();
            GatewayAttempt current = findAttempt(company, attemptId).orElse(null);
            if (current == null || current.status() == GatewayAttemptStatus.CANCELLED
                    || current.status() == GatewayAttemptStatus.SETTLED) {
                return;
            }
            if (current.status() != GatewayAttemptStatus.RESERVED) {
                // Already sending / pending: keep freeze for reconciliation.
                return;
            }
            long r = current.reservedTokens();
            jdbc.update("""
                    UPDATE gateway_account
                    SET available_tokens = available_tokens + ?, reserved_tokens = reserved_tokens - ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                      AND reserved_tokens >= ?
                    """,
                    r, r, company, current.period(), GatewayAccountType.EMPLOYEE.name(),
                    current.payerUserId(), r);
            jdbc.update("""
                    UPDATE gateway_attempt SET status = ?
                    WHERE company_id = ? AND id = ? AND status = ?
                    """,
                    GatewayAttemptStatus.CANCELLED.name(), company, attemptId,
                    GatewayAttemptStatus.RESERVED.name());
            writeLedger(company, "cancel:" + attemptId, 1, current.period(), current.payerUserId(),
                    r, -r, 0, "CANCEL", attemptId);
        });
    }

    private void writeLedger(String company, String operationId, int entryNo, String period, String ownerId,
                             long dAvailable, long dReserved, long dSettled, String reason, String attemptId) {
        jdbc.update("""
                INSERT INTO gateway_ledger (company_id, operation_id, entry_no, period, account_type, owner_id,
                  delta_available, delta_reserved, delta_settled, reason, attempt_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                company, operationId, entryNo, period, GatewayAccountType.EMPLOYEE.name(), ownerId,
                dAvailable, dReserved, dSettled, reason, attemptId);
    }

    private GatewayAttempt requireAttempt(String company, String attemptId) {
        return findAttempt(company, attemptId)
                .orElseThrow(() -> new IllegalStateException("gateway attempt not found: " + attemptId));
    }

    private Optional<GatewayAttempt> findAttempt(String company, String attemptId) {
        return jdbc.query("""
                        SELECT id, request_id, attempt_no, turn_id, run_id, payer_user_id, cli, model, period,
                          reserved_tokens, actual_tokens, status
                        FROM gateway_attempt WHERE company_id = ? AND id = ?
                        """,
                ATTEMPT, company, attemptId).stream().findFirst();
    }

    private static void validateReserve(ReserveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.requestId() == null || request.requestId().isBlank()
                || request.turnId() == null || request.turnId().isBlank()
                || request.runId() == null || request.runId().isBlank()
                || request.payerUserId() == null || request.payerUserId().isBlank()
                || request.cli() == null || request.cli().isBlank()
                || request.period() == null || request.period().isBlank()) {
            throw new IllegalArgumentException("reserve fields are required");
        }
        if (request.reservedTokens() <= 0) {
            throw new IllegalArgumentException("reservedTokens must be > 0");
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
