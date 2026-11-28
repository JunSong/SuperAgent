package com.superagent.storage.jdbc;

import com.superagent.core.gateway.GatewayAccount;
import com.superagent.core.gateway.GatewayAccountType;
import com.superagent.core.gateway.GatewayAttempt;
import com.superagent.core.gateway.GatewayAttemptStatus;
import com.superagent.core.gateway.GatewayConflictException;
import com.superagent.core.gateway.GatewayLedgerEntry;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayProjectionEvent;
import com.superagent.core.gateway.GatewayQuotaExhaustedException;
import com.superagent.core.gateway.GatewayQuotaInsufficientException;
import com.superagent.core.gateway.GatewayQuotaMode;
import com.superagent.core.gateway.GatewaySettlementUsage;
import com.superagent.core.usage.UsageContext;
import com.superagent.core.usage.UsageRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MySQL/H2 ledger for #34: reserve/beginSend/settle, float claim (PR-04), allocate/reclaim/transfer (PR-05).
 */
public class JdbcGatewayMetering implements GatewayMetering, GatewaySettlementUsage {

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
              base_quota        BIGINT       NOT NULL DEFAULT 0,
              float_allowance   BIGINT       NOT NULL DEFAULT 0,
              float_turn_id     VARCHAR(128) NULL,
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
              param_digest  VARCHAR(128) NULL,
              PRIMARY KEY (company_id, operation_id, entry_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    public static final String PROJECTION_DDL = """
            CREATE TABLE IF NOT EXISTS gateway_projection (
              company_id      VARCHAR(64)  NOT NULL,
              id              VARCHAR(64)  NOT NULL,
              period          VARCHAR(7)   NOT NULL,
              attempt_id      VARCHAR(64)  NOT NULL,
              event_type      VARCHAR(32)  NOT NULL,
              line_no         INT          NOT NULL,
              payload_digest  VARCHAR(128) NOT NULL,
              payer_user_id   VARCHAR(128) NOT NULL,
              cli             VARCHAR(64)  NOT NULL,
              model           VARCHAR(128) NOT NULL,
              turn_id         VARCHAR(128) NOT NULL,
              run_id          VARCHAR(128) NOT NULL,
              actual_tokens   BIGINT       NOT NULL,
              at_time         DATETIME(3)  NOT NULL,
              PRIMARY KEY (company_id, id),
              UNIQUE KEY uk_gateway_projection_biz (company_id, attempt_id, event_type, line_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private static final String POOL_OWNER = "";
    private static final String EVENT_SETTLEMENT = "settlement";

    private static final RowMapper<GatewayAccount> ACCOUNT = (rs, n) -> new GatewayAccount(
            rs.getString("period"),
            GatewayAccountType.valueOf(rs.getString("account_type")),
            rs.getString("owner_id"),
            GatewayQuotaMode.valueOf(rs.getString("quota_mode")),
            rs.getLong("available_tokens"),
            rs.getLong("reserved_tokens"),
            rs.getLong("settled_tokens"),
            rs.getLong("base_quota"),
            rs.getLong("float_allowance"),
            blankToNull(rs.getString("float_turn_id")));

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

    private static final RowMapper<GatewayLedgerEntry> LEDGER = (rs, n) -> new GatewayLedgerEntry(
            rs.getString("operation_id"),
            rs.getInt("entry_no"),
            rs.getString("period"),
            GatewayAccountType.valueOf(rs.getString("account_type")),
            rs.getString("owner_id"),
            rs.getLong("delta_available"),
            rs.getLong("delta_reserved"),
            rs.getLong("delta_settled"),
            rs.getString("reason"),
            rs.getString("attempt_id"));

    private static final RowMapper<GatewayProjectionEvent> PROJECTION = (rs, n) -> new GatewayProjectionEvent(
            rs.getString("id"),
            rs.getString("attempt_id"),
            rs.getString("event_type"),
            rs.getInt("line_no"),
            rs.getString("payload_digest"),
            rs.getString("payer_user_id"),
            rs.getString("cli"),
            rs.getString("model"),
            rs.getString("turn_id"),
            rs.getString("run_id"),
            rs.getLong("actual_tokens"),
            rs.getObject("at_time", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcGatewayMetering(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "JdbcTemplate needs a DataSource")));
        TenantSchema.create(jdbc, ACCOUNT_DDL);
        TenantSchema.create(jdbc, ATTEMPT_DDL);
        TenantSchema.create(jdbc, LEDGER_DDL);
        TenantSchema.create(jdbc, PROJECTION_DDL);
        ensureColumn("gateway_account", "base_quota", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn("gateway_account", "float_allowance", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn("gateway_account", "float_turn_id", "VARCHAR(128) NULL");
        ensureColumn("gateway_ledger", "param_digest", "VARCHAR(128) NULL");
    }

    @Override
    public void putEmployee(String period, String userId, long available) {
        putEmployee(period, userId, available, 0, available, 0);
    }

    @Override
    public void putEmployee(String period, String userId, long available, long settled, long baseQuota,
                            long floatAllowance) {
        requirePeriodUser(period, userId);
        if (available < 0 || settled < 0 || baseQuota < 0 || floatAllowance < 0) {
            throw new IllegalArgumentException("balances must be >= 0");
        }
        String company = TenantSchema.company();
        // Upsert without wiping reserved_tokens / float_turn_id (REPLACE INTO would drop in-flight freezes).
        jdbc.update("""
                INSERT INTO gateway_account (company_id, period, account_type, owner_id, quota_mode,
                  available_tokens, reserved_tokens, settled_tokens, base_quota, float_allowance, float_turn_id)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, NULL)
                ON DUPLICATE KEY UPDATE
                  available_tokens = VALUES(available_tokens),
                  settled_tokens = VALUES(settled_tokens),
                  base_quota = VALUES(base_quota),
                  float_allowance = VALUES(float_allowance),
                  quota_mode = VALUES(quota_mode)
                """,
                company, period, GatewayAccountType.EMPLOYEE.name(), userId,
                GatewayQuotaMode.LIMITED.name(), available, settled, baseQuota, floatAllowance);
    }

    @Override
    public void putPool(String period, GatewayAccountType type, long available) {
        if (period == null || period.isBlank() || type == null || type == GatewayAccountType.EMPLOYEE) {
            throw new IllegalArgumentException("period and non-employee type are required");
        }
        if (available < 0) {
            throw new IllegalArgumentException("available must be >= 0");
        }
        jdbc.update("""
                INSERT INTO gateway_account (company_id, period, account_type, owner_id, quota_mode,
                  available_tokens, reserved_tokens, settled_tokens, base_quota, float_allowance, float_turn_id)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, 0, NULL)
                ON DUPLICATE KEY UPDATE
                  available_tokens = VALUES(available_tokens),
                  base_quota = VALUES(base_quota),
                  quota_mode = VALUES(quota_mode)
                """,
                TenantSchema.company(), period, type.name(), POOL_OWNER, GatewayQuotaMode.LIMITED.name(),
                available, available);
    }

    @Override
    public long fundReconReserveToTarget(String period, long target) {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("period is required");
        }
        if (target <= 0) {
            return 0L;
        }
        Long moved = tx.execute(status -> {
            String company = TenantSchema.company();
            ensurePoolRow(company, period, GatewayAccountType.RECON_RESERVE);
            ensurePoolRow(company, period, GatewayAccountType.UNALLOCATED);
            long reconAvail = lockPoolAvailable(company, period, GatewayAccountType.RECON_RESERVE);
            long unallocAvail = lockPoolAvailable(company, period, GatewayAccountType.UNALLOCATED);
            long need = target - reconAvail;
            if (need <= 0) {
                return 0L;
            }
            long move = Math.min(need, unallocAvail);
            if (move <= 0) {
                return 0L;
            }
            int debit = jdbc.update("""
                    UPDATE gateway_account SET available_tokens = available_tokens - ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                      AND available_tokens >= ?
                    """,
                    move, company, period, GatewayAccountType.UNALLOCATED.name(), POOL_OWNER, move);
            int credit = jdbc.update("""
                    UPDATE gateway_account SET available_tokens = available_tokens + ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                    """,
                    move, company, period, GatewayAccountType.RECON_RESERVE.name(), POOL_OWNER);
            if (debit != 1 || credit != 1) {
                throw new IllegalStateException("RECON fund transfer failed");
            }
            String operationId = "recon-fund:" + period + ":" + UUID.randomUUID();
            String digest = digest("recon-fund", period, move);
            writeLedger(company, operationId, 1, period, GatewayAccountType.UNALLOCATED, POOL_OWNER,
                    -move, 0, 0, "RECON_FUND_OUT", null, digest);
            writeLedger(company, operationId, 2, period, GatewayAccountType.RECON_RESERVE, POOL_OWNER,
                    move, 0, 0, "RECON_FUND_IN", null, digest);
            return move;
        });
        return moved == null ? 0L : moved;
    }

    @Override
    public Optional<GatewayAccount> findEmployee(String period, String userId) {
        return findAccount(period, GatewayAccountType.EMPLOYEE, userId);
    }

    @Override
    public List<GatewayAccount> listEmployees(String period) {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("period is required");
        }
        return jdbc.query("""
                        SELECT period, account_type, owner_id, quota_mode, available_tokens, reserved_tokens,
                          settled_tokens, base_quota, float_allowance, float_turn_id
                        FROM gateway_account
                        WHERE company_id = ? AND period = ? AND account_type = ?
                        ORDER BY owner_id
                        """,
                ACCOUNT, TenantSchema.company(), period, GatewayAccountType.EMPLOYEE.name());
    }

    @Override
    public Optional<GatewayAccount> findAccount(String period, GatewayAccountType type, String ownerId) {
        String owner = ownerId == null ? POOL_OWNER : ownerId;
        return jdbc.query("""
                        SELECT period, account_type, owner_id, quota_mode, available_tokens, reserved_tokens,
                          settled_tokens, base_quota, float_allowance, float_turn_id
                        FROM gateway_account
                        WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                        """,
                ACCOUNT, TenantSchema.company(), period, type.name(), owner).stream().findFirst();
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
            long used = account.settled() + account.reserved();
            long b = account.baseQuota() > 0 ? account.baseQuota() : account.available() + account.reserved() + account.settled();
            long f = account.floatAllowance();
            String floatTurn = account.floatTurnId();
            boolean isFloatTurn = floatTurn != null && floatTurn.equals(request.turnId());

            // New turns cannot open on float alone once base is exhausted (#34 / R04).
            // Same turn that already reserved under B may continue and claim F (tool round-trips).
            if (used >= b && !isFloatTurn) {
                boolean sameTurnContinuation = f > 0 && floatTurn == null
                        && hasAttemptForTurn(company, request.period(), request.payerUserId(), request.turnId());
                if (!sameTurnContinuation) {
                    throw new GatewayQuotaExhaustedException(
                            "本月基础额度已用尽（QUOTA_EXHAUSTED）");
                }
                // fall through: claim float for this continuing turn
            }

            if (used + r > b || (used >= b && !isFloatTurn && floatTurn == null)) {
                if (floatTurn == null) {
                    // Crossing B, or continuing past exact B on the same turn: claim F for this turn only.
                    if (f <= 0) {
                        throw new GatewayQuotaInsufficientException(
                                "可用额度不足：需要预占 " + r + " tokens（QUOTA_INSUFFICIENT）");
                    }
                    int claimed = jdbc.update("""
                            UPDATE gateway_account
                            SET float_turn_id = ?, available_tokens = available_tokens + ?
                            WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                              AND (float_turn_id IS NULL OR float_turn_id = '')
                            """,
                            request.turnId(), f, company, request.period(),
                            GatewayAccountType.EMPLOYEE.name(), request.payerUserId());
                    if (claimed == 0) {
                        throw new GatewayQuotaExhaustedException(
                                "上浮资格已被其他轮次占用（QUOTA_EXHAUSTED）");
                    }
                    writeLedger(company, "float-claim:" + request.turnId(), 1, request.period(),
                            GatewayAccountType.EMPLOYEE, request.payerUserId(), f, 0, 0, "FLOAT_CLAIM", null,
                            digest("float", request.turnId(), f));
                    isFloatTurn = true;
                    // pull F from FLOAT_POOL when present
                    debitPoolIfPresent(company, request.period(), GatewayAccountType.FLOAT_POOL, f);
                } else if (!isFloatTurn) {
                    throw new GatewayQuotaExhaustedException(
                            "本月基础额度已用尽且上浮属于其他轮次（QUOTA_EXHAUSTED）");
                }
            }

            long cap = b + (isFloatTurn ? f : 0);
            if (used + r > cap) {
                throw new GatewayQuotaInsufficientException(
                        "可用额度不足：需要预占 " + r + " tokens（QUOTA_INSUFFICIENT）");
            }

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
            writeLedger(company, "reserve:" + attemptId, 1, request.period(),
                    GatewayAccountType.EMPLOYEE, request.payerUserId(),
                    -r, r, 0, "RESERVE", attemptId, null);
            return requireAttempt(company, attemptId);
        });
    }

    @Override
    public boolean beginSend(String attemptId) {
        if (attemptId == null || attemptId.isBlank()) {
            return false;
        }
        Boolean ok = tx.execute(status -> jdbc.update("""
                UPDATE gateway_attempt SET status = ?
                WHERE company_id = ? AND id = ? AND status = ?
                """,
                GatewayAttemptStatus.SENDING.name(), TenantSchema.company(), attemptId,
                GatewayAttemptStatus.RESERVED.name()) == 1);
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
            // Lock order: attempt → RECON pool → employee (H01). Never reopen a committed SETTLED.
            GatewayAttempt current = lockAttempt(company, attemptId);
            if (current.status() == GatewayAttemptStatus.SETTLED) {
                if (current.actualTokens() == actualTokens) {
                    return current;
                }
                throw new IllegalStateException("attempt already settled with A=" + current.actualTokens());
            }
            if (current.status() != GatewayAttemptStatus.SENDING
                    && current.status() != GatewayAttemptStatus.PENDING_RECONCILIATION) {
                throw new IllegalStateException("cannot settle attempt in status " + current.status());
            }
            long r = current.reservedTokens();
            long a = actualTokens; // full vendor usage — never silently truncate (C1 / R03)
            long employeeCharge = Math.min(a, r);
            long overage = a - employeeCharge;
            long refund = r - employeeCharge;
            // Debit RECON before releasing the employee freeze so a concurrent drain cannot leave us
            // charged-but-pending (which would steal another attempt's reserved on retry).
            if (overage > 0 && !tryDebitReconReserve(company, current.period(), overage, attemptId)) {
                int pending = jdbc.update("""
                        UPDATE gateway_attempt SET status = ?, actual_tokens = ?
                        WHERE company_id = ? AND id = ? AND status IN (?, ?)
                        """,
                        GatewayAttemptStatus.PENDING_RECONCILIATION.name(), a, company, attemptId,
                        GatewayAttemptStatus.SENDING.name(),
                        GatewayAttemptStatus.PENDING_RECONCILIATION.name());
                if (pending == 0) {
                    return settleReplayAfterRace(company, attemptId, actualTokens);
                }
                return requireAttempt(company, attemptId);
            }
            int moved = jdbc.update("""
                    UPDATE gateway_account
                    SET available_tokens = available_tokens + ?,
                        reserved_tokens = reserved_tokens - ?,
                        settled_tokens = settled_tokens + ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                      AND reserved_tokens >= ?
                    """,
                    refund, r, employeeCharge, company, current.period(), GatewayAccountType.EMPLOYEE.name(),
                    current.payerUserId(), r);
            if (moved == 0) {
                // RECON already taken for overage — refund it so we do not leak company reserve.
                if (overage > 0) {
                    creditPool(company, current.period(), GatewayAccountType.RECON_RESERVE, overage);
                }
                throw new IllegalStateException("account reserved balance missing for attempt " + attemptId);
            }
            int settled = jdbc.update("""
                    UPDATE gateway_attempt SET status = ?, actual_tokens = ?
                    WHERE company_id = ? AND id = ? AND status IN (?, ?)
                    """,
                    GatewayAttemptStatus.SETTLED.name(), a, company, attemptId,
                    GatewayAttemptStatus.SENDING.name(),
                    GatewayAttemptStatus.PENDING_RECONCILIATION.name());
            if (settled == 0) {
                if (overage > 0) {
                    creditPool(company, current.period(), GatewayAccountType.RECON_RESERVE, overage);
                }
                // Undo employee move — concurrent winner already owns the terminal state.
                jdbc.update("""
                        UPDATE gateway_account
                        SET available_tokens = available_tokens - ?,
                            reserved_tokens = reserved_tokens + ?,
                            settled_tokens = settled_tokens - ?
                        WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                        """,
                        refund, r, employeeCharge, company, current.period(),
                        GatewayAccountType.EMPLOYEE.name(), current.payerUserId());
                return settleReplayAfterRace(company, attemptId, actualTokens);
            }
            writeLedger(company, "settle:" + attemptId, 1, current.period(),
                    GatewayAccountType.EMPLOYEE, current.payerUserId(),
                    refund, -r, employeeCharge, "SETTLE", attemptId, null);
            writeSettlementProjection(company, current, a);
            return requireAttempt(company, attemptId);
        });
    }

    @Override
    public GatewayAttempt markPending(String attemptId) {
        return markPending(attemptId, -1);
    }

    @Override
    public GatewayAttempt markPending(String attemptId, long actualTokens) {
        return tx.execute(status -> {
            String company = TenantSchema.company();
            // Same lock as settle so a concurrent settle cannot be overwritten by a late A (I-3).
            GatewayAttempt current = lockAttempt(company, attemptId);
            if (current.status() == GatewayAttemptStatus.SETTLED
                    || current.status() == GatewayAttemptStatus.CANCELLED) {
                return current;
            }
            if (current.status() == GatewayAttemptStatus.PENDING_RECONCILIATION) {
                if (actualTokens >= 0 && current.actualTokens() != actualTokens) {
                    int n = jdbc.update("""
                            UPDATE gateway_attempt SET actual_tokens = ?
                            WHERE company_id = ? AND id = ? AND status = ?
                            """,
                            actualTokens, company, attemptId,
                            GatewayAttemptStatus.PENDING_RECONCILIATION.name());
                    if (n != 1) {
                        return requireAttempt(company, attemptId);
                    }
                    return requireAttempt(company, attemptId);
                }
                return current;
            }
            int n;
            if (actualTokens >= 0) {
                n = jdbc.update("""
                        UPDATE gateway_attempt SET status = ?, actual_tokens = ?
                        WHERE company_id = ? AND id = ? AND status IN (?, ?)
                        """,
                        GatewayAttemptStatus.PENDING_RECONCILIATION.name(), actualTokens, company, attemptId,
                        GatewayAttemptStatus.SENDING.name(), GatewayAttemptStatus.RESERVED.name());
            } else {
                n = jdbc.update("""
                        UPDATE gateway_attempt SET status = ?
                        WHERE company_id = ? AND id = ? AND status IN (?, ?)
                        """,
                        GatewayAttemptStatus.PENDING_RECONCILIATION.name(), company, attemptId,
                        GatewayAttemptStatus.SENDING.name(), GatewayAttemptStatus.RESERVED.name());
            }
            if (n != 1) {
                // Lost the race to settle/cancel — return whatever won.
                return requireAttempt(company, attemptId);
            }
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
            if (current == null || current.status() != GatewayAttemptStatus.RESERVED) {
                return;
            }
            long r = current.reservedTokens();
            // Attempt first: if settle won the race, both updates no-op and we write no ledger (C5).
            int cancelled = jdbc.update("""
                    UPDATE gateway_attempt SET status = ?
                    WHERE company_id = ? AND id = ? AND status = ?
                    """,
                    GatewayAttemptStatus.CANCELLED.name(), company, attemptId,
                    GatewayAttemptStatus.RESERVED.name());
            if (cancelled != 1) {
                return;
            }
            int released = jdbc.update("""
                    UPDATE gateway_account
                    SET available_tokens = available_tokens + ?, reserved_tokens = reserved_tokens - ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                      AND reserved_tokens >= ?
                    """,
                    r, r, company, current.period(), GatewayAccountType.EMPLOYEE.name(),
                    current.payerUserId(), r);
            if (released != 1) {
                throw new IllegalStateException("cancel released 0 account rows for attempt " + attemptId);
            }
            writeLedger(company, "cancel:" + attemptId, 1, current.period(),
                    GatewayAccountType.EMPLOYEE, current.payerUserId(),
                    r, -r, 0, "CANCEL", attemptId, null);
        });
    }

    @Override
    public void abortUnsent(String attemptId) {
        if (attemptId == null || attemptId.isBlank()) {
            return;
        }
        tx.executeWithoutResult(status -> {
            String company = TenantSchema.company();
            // Lock so a concurrent settle/pending cannot be overwritten by a late abort.
            GatewayAttempt current;
            try {
                current = lockAttempt(company, attemptId);
            } catch (IllegalStateException missing) {
                return;
            }
            if (current.status() == GatewayAttemptStatus.CANCELLED) {
                return; // idempotent
            }
            if (current.status() == GatewayAttemptStatus.SETTLED
                    || current.status() == GatewayAttemptStatus.PENDING_RECONCILIATION) {
                // Outbound may have occurred — never refund from this path.
                return;
            }
            if (current.status() != GatewayAttemptStatus.RESERVED
                    && current.status() != GatewayAttemptStatus.SENDING) {
                return;
            }
            long r = current.reservedTokens();
            int cancelled = jdbc.update("""
                    UPDATE gateway_attempt SET status = ?
                    WHERE company_id = ? AND id = ? AND status IN (?, ?)
                    """,
                    GatewayAttemptStatus.CANCELLED.name(), company, attemptId,
                    GatewayAttemptStatus.RESERVED.name(), GatewayAttemptStatus.SENDING.name());
            if (cancelled != 1) {
                return;
            }
            int released = jdbc.update("""
                    UPDATE gateway_account
                    SET available_tokens = available_tokens + ?, reserved_tokens = reserved_tokens - ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                      AND reserved_tokens >= ?
                    """,
                    r, r, company, current.period(), GatewayAccountType.EMPLOYEE.name(),
                    current.payerUserId(), r);
            if (released != 1) {
                throw new IllegalStateException("abortUnsent released 0 account rows for attempt " + attemptId);
            }
            writeLedger(company, "abort-unsent:" + attemptId, 1, current.period(),
                    GatewayAccountType.EMPLOYEE, current.payerUserId(),
                    r, -r, 0, "ABORT_UNSENT", attemptId, null);
        });
    }

    @Override
    public GatewayAccount allocate(String operationId, String period, String toUserId, long amount, String actorUserId) {
        requireOp(operationId, period, toUserId, amount);
        return tx.execute(status -> {
            String digest = digest("allocate", toUserId, amount);
            if (replayOrConflict(operationId, digest)) {
                return findEmployee(period, toUserId).orElseThrow();
            }
            String company = TenantSchema.company();
            ensureEmployeeRow(company, period, toUserId);
            debitPool(company, period, GatewayAccountType.UNALLOCATED, amount);
            jdbc.update("""
                    UPDATE gateway_account
                    SET available_tokens = available_tokens + ?, base_quota = base_quota + ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                    """,
                    amount, amount, company, period, GatewayAccountType.EMPLOYEE.name(), toUserId);
            writeLedger(company, operationId, 1, period, GatewayAccountType.UNALLOCATED, POOL_OWNER,
                    -amount, 0, 0, "ALLOCATE_OUT", null, digest);
            writeLedger(company, operationId, 2, period, GatewayAccountType.EMPLOYEE, toUserId,
                    amount, 0, 0, "ALLOCATE_IN", null, digest);
            return findEmployee(period, toUserId).orElseThrow();
        });
    }

    @Override
    public GatewayAccount setFloatAllowance(String period, String userId, long floatAllowance) {
        requirePeriodUser(period, userId);
        if (floatAllowance < 0) {
            throw new IllegalArgumentException("floatAllowance must be >= 0");
        }
        return tx.execute(status -> {
            String company = TenantSchema.company();
            ensureEmployeeRow(company, period, userId);
            jdbc.update("""
                    UPDATE gateway_account SET float_allowance = ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                    """,
                    floatAllowance, company, period, GatewayAccountType.EMPLOYEE.name(), userId);
            return findEmployee(period, userId).orElseThrow();
        });
    }

    @Override
    public int applyDefaultFloat(String period, long floatAllowance) {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("period is required");
        }
        if (floatAllowance < 0) {
            throw new IllegalArgumentException("floatAllowance must be >= 0");
        }
        if (floatAllowance == 0) {
            return 0;
        }
        Integer n = tx.execute(status -> jdbc.update("""
                UPDATE gateway_account SET float_allowance = ?
                WHERE company_id = ? AND period = ? AND account_type = ?
                  AND float_allowance = 0
                """,
                floatAllowance, TenantSchema.company(), period, GatewayAccountType.EMPLOYEE.name()));
        return n == null ? 0 : n;
    }

    @Override
    public GatewayAccount reclaim(String operationId, String period, String fromUserId, long amount, String actorUserId) {
        requireOp(operationId, period, fromUserId, amount);
        return tx.execute(status -> {
            String digest = digest("reclaim", fromUserId, amount);
            if (replayOrConflict(operationId, digest)) {
                return findEmployee(period, fromUserId).orElseThrow();
            }
            String company = TenantSchema.company();
            // Only base remainder is reclaimable — not float that was merged into available (R05).
            int n = jdbc.update("""
                    UPDATE gateway_account
                    SET available_tokens = available_tokens - ?, base_quota = GREATEST(0, base_quota - ?)
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                      AND available_tokens >= ?
                      AND ? <= GREATEST(0, base_quota - settled_tokens - reserved_tokens)
                    """,
                    amount, amount, company, period, GatewayAccountType.EMPLOYEE.name(), fromUserId, amount, amount);
            if (n == 0) {
                throw new GatewayQuotaInsufficientException("可回收余额不足（上浮额度不可回收）");
            }
            ensurePoolRow(company, period, GatewayAccountType.UNALLOCATED);
            jdbc.update("""
                    UPDATE gateway_account SET available_tokens = available_tokens + ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                    """,
                    amount, company, period, GatewayAccountType.UNALLOCATED.name(), POOL_OWNER);
            writeLedger(company, operationId, 1, period, GatewayAccountType.EMPLOYEE, fromUserId,
                    -amount, 0, 0, "RECLAIM_OUT", null, digest);
            writeLedger(company, operationId, 2, period, GatewayAccountType.UNALLOCATED, POOL_OWNER,
                    amount, 0, 0, "RECLAIM_IN", null, digest);
            return findEmployee(period, fromUserId).orElseThrow();
        });
    }

    @Override
    public void transfer(String operationId, String period, String fromUserId, String toUserId, long amount,
                         String actorUserId) {
        requireOp(operationId, period, fromUserId, amount);
        if (toUserId == null || toUserId.isBlank() || toUserId.equals(fromUserId)) {
            throw new IllegalArgumentException("toUserId must be another employee");
        }
        tx.executeWithoutResult(status -> {
            String digest = digest("transfer", fromUserId + ">" + toUserId, amount);
            if (replayOrConflict(operationId, digest)) {
                return;
            }
            String company = TenantSchema.company();
            ensureEmployeeRow(company, period, toUserId);
            // Float remainder rides in available_tokens but must not become another employee's B (R05).
            int n = jdbc.update("""
                    UPDATE gateway_account SET available_tokens = available_tokens - ?,
                      base_quota = GREATEST(0, base_quota - ?)
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                      AND available_tokens >= ?
                      AND ? <= GREATEST(0, base_quota - settled_tokens - reserved_tokens)
                    """,
                    amount, amount, company, period, GatewayAccountType.EMPLOYEE.name(), fromUserId, amount, amount);
            if (n == 0) {
                throw new GatewayQuotaInsufficientException("可用余额不足，无法转账（上浮额度不可转让）");
            }
            jdbc.update("""
                    UPDATE gateway_account SET available_tokens = available_tokens + ?,
                      base_quota = base_quota + ?
                    WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                    """,
                    amount, amount, company, period, GatewayAccountType.EMPLOYEE.name(), toUserId);
            writeLedger(company, operationId, 1, period, GatewayAccountType.EMPLOYEE, fromUserId,
                    -amount, 0, 0, "TRANSFER_OUT", null, digest);
            writeLedger(company, operationId, 2, period, GatewayAccountType.EMPLOYEE, toUserId,
                    amount, 0, 0, "TRANSFER_IN", null, digest);
        });
    }

    @Override
    public List<GatewayLedgerEntry> listLedger(String period, int limit) {
        int n = Math.min(Math.max(limit, 1), 500);
        return jdbc.query("""
                        SELECT operation_id, entry_no, period, account_type, owner_id,
                          delta_available, delta_reserved, delta_settled, reason, attempt_id
                        FROM gateway_ledger
                        WHERE company_id = ? AND period = ?
                        ORDER BY operation_id DESC, entry_no ASC
                        LIMIT ?
                        """,
                LEDGER, TenantSchema.company(), period, n);
    }

    @Override
    public List<GatewayAttempt> listAttempts(GatewayAttemptStatus status, String period, int limit) {
        if (status == null || period == null || period.isBlank()) {
            throw new IllegalArgumentException("status and period are required");
        }
        int n = Math.min(Math.max(limit, 1), 500);
        return jdbc.query("""
                        SELECT id, request_id, attempt_no, turn_id, run_id, payer_user_id, cli, model, period,
                          reserved_tokens, actual_tokens, status
                        FROM gateway_attempt
                        WHERE company_id = ? AND period = ? AND status = ?
                        ORDER BY id DESC
                        LIMIT ?
                        """,
                ATTEMPT, TenantSchema.company(), period, status.name(), n);
    }

    @Override
    public List<GatewayProjectionEvent> listProjections(String period, int limit) {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("period is required");
        }
        int n = Math.min(Math.max(limit, 1), 500);
        return jdbc.query("""
                        SELECT id, attempt_id, event_type, line_no, payload_digest, payer_user_id, cli, model,
                          turn_id, run_id, actual_tokens, at_time
                        FROM gateway_projection
                        WHERE company_id = ? AND period = ?
                        ORDER BY at_time DESC, line_no ASC
                        LIMIT ?
                        """,
                PROJECTION, TenantSchema.company(), period, n);
    }

    @Override
    public List<UsageRecord> between(String user, Instant from, Instant to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        List<Object> args = new ArrayList<>();
        args.add(TenantSchema.company());
        args.add(LocalDateTime.ofInstant(from, ZoneOffset.UTC));
        args.add(LocalDateTime.ofInstant(to, ZoneOffset.UTC));
        String byUser = "";
        if (user != null) {
            byUser = " AND payer_user_id = ?";
            args.add(user);
        }
        String sql = """
                SELECT id, attempt_id, event_type, line_no, payload_digest, payer_user_id, cli, model,
                  turn_id, run_id, actual_tokens, at_time
                FROM gateway_projection
                WHERE company_id = ? AND event_type = 'settlement'
                  AND at_time >= ? AND at_time < ?
                """ + byUser + " ORDER BY at_time ASC, line_no ASC";
        return jdbc.query(sql, PROJECTION, args.toArray()).stream()
                .map(JdbcGatewayMetering::toUsageRecord)
                .toList();
    }

    /**
     * Vendor total A is one number; park it in input so {@link UsageRecord#total()} matches A.
     * Managed {@code runId} is a dedicated field so chat {@code sessionId} stays untouched.
     */
    private static UsageRecord toUsageRecord(GatewayProjectionEvent e) {
        return new UsageRecord(
                e.id(),
                e.at(),
                e.payerUserId(),
                UsageContext.SOURCE_GATEWAY,
                null,
                null,
                null,
                e.cli(),
                e.model(),
                e.actualTokens(),
                0, 0, 0, 0, 0, 0,
                null,
                null,
                e.runId());
    }

    /** Max IN-list size per query — never drop candidates; callers may send thousands of run ids. */
    static final int SETTLED_RUN_ID_BATCH = 500;

    @Override
    public List<UsageRecord> settlementsForRunIds(String user, java.util.Collection<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = runIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UsageRecord> out = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += SETTLED_RUN_ID_BATCH) {
            int to = Math.min(from + SETTLED_RUN_ID_BATCH, ids.size());
            out.addAll(querySettlementsForRunIdsBatch(user, ids.subList(from, to)));
        }
        return out;
    }

    @Override
    public java.util.Set<String> settledRunIds(String user, java.util.Collection<String> runIds) {
        return settlementsForRunIds(user, runIds).stream()
                .map(UsageRecord::runId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private List<UsageRecord> querySettlementsForRunIdsBatch(String user, List<String> batch) {
        String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(TenantSchema.company());
        args.addAll(batch);
        String byUser = "";
        if (user != null) {
            byUser = " AND payer_user_id = ?";
            args.add(user);
        }
        String sql = """
                SELECT id, attempt_id, event_type, line_no, payload_digest, payer_user_id, cli, model,
                  turn_id, run_id, actual_tokens, at_time
                FROM gateway_projection
                WHERE company_id = ? AND event_type = 'settlement'
                  AND run_id IN (%s)
                """.formatted(placeholders) + byUser + " ORDER BY at_time ASC, line_no ASC";
        return jdbc.query(sql, PROJECTION, args.toArray()).stream()
                .map(JdbcGatewayMetering::toUsageRecord)
                .toList();
    }

    /**
     * Same transaction as settle: idempotent on (attemptId, settlement, lineNo=1).
     * Same digest → ok; different digest → conflict.
     */
    private void writeSettlementProjection(String company, GatewayAttempt attempt, long actualTokens) {
        int lineNo = 1;
        String id = sha256Hex(company + "\0" + attempt.id() + "\0" + EVENT_SETTLEMENT + "\0" + lineNo);
        String payloadDigest = sha256Hex(attempt.payerUserId() + "|" + attempt.cli() + "|" + nullToEmpty(attempt.model())
                + "|" + attempt.turnId() + "|" + attempt.runId() + "|" + actualTokens);
        Instant at = Instant.now();
        try {
            jdbc.update("""
                    INSERT INTO gateway_projection (company_id, id, period, attempt_id, event_type, line_no,
                      payload_digest, payer_user_id, cli, model, turn_id, run_id, actual_tokens, at_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    company, id, attempt.period(), attempt.id(), EVENT_SETTLEMENT, lineNo, payloadDigest,
                    attempt.payerUserId(), attempt.cli(), nullToEmpty(attempt.model()), attempt.turnId(),
                    attempt.runId(), actualTokens, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        } catch (DuplicateKeyException e) {
            List<String> existing = jdbc.query("""
                            SELECT payload_digest FROM gateway_projection
                            WHERE company_id = ? AND attempt_id = ? AND event_type = ? AND line_no = ?
                            """,
                    (rs, n) -> rs.getString(1), company, attempt.id(), EVENT_SETTLEMENT, lineNo);
            if (!existing.isEmpty() && Objects.equals(existing.getFirst(), payloadDigest)) {
                return;
            }
            throw new GatewayConflictException("settlement projection digest mismatch for attempt " + attempt.id());
        }
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * True when this turn already consumed base via a live attempt (R04). CANCELLED / RESERVED-only
     * ghosts must not unlock float for a "continuation" after freeze was released.
     */
    @Override
    public boolean hasAttemptForTurn(String period, String payerUserId, String turnId) {
        return hasAttemptForTurn(TenantSchema.company(), period, payerUserId, turnId);
    }

    private boolean hasAttemptForTurn(String company, String period, String payerUserId, String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return false;
        }
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM gateway_attempt
                WHERE company_id = ? AND period = ? AND payer_user_id = ? AND turn_id = ?
                  AND status IN (?, ?, ?)
                """,
                Integer.class, company, period, payerUserId, turnId,
                GatewayAttemptStatus.SETTLED.name(),
                GatewayAttemptStatus.SENDING.name(),
                GatewayAttemptStatus.PENDING_RECONCILIATION.name());
        return n != null && n > 0;
    }

    /** Debits RECON_RESERVE for A−R; returns false when the pool cannot cover (caller keeps PENDING + A). */
    private boolean tryDebitReconReserve(String company, String period, long overage, String attemptId) {
        ensurePoolRow(company, period, GatewayAccountType.RECON_RESERVE);
        int n = jdbc.update("""
                UPDATE gateway_account SET available_tokens = available_tokens - ?
                WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                  AND available_tokens >= ?
                """,
                overage, company, period, GatewayAccountType.RECON_RESERVE.name(), POOL_OWNER, overage);
        if (n == 0) {
            return false;
        }
        writeLedger(company, "overage:" + attemptId, 1, period, GatewayAccountType.RECON_RESERVE, POOL_OWNER,
                -overage, 0, 0, "OVERAGE", attemptId, digest("overage", attemptId, overage));
        return true;
    }

    private void creditPool(String company, String period, GatewayAccountType type, long amount) {
        ensurePoolRow(company, period, type);
        jdbc.update("""
                UPDATE gateway_account SET available_tokens = available_tokens + ?
                WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                """,
                amount, company, period, type.name(), POOL_OWNER);
    }

    private void debitPoolIfPresent(String company, String period, GatewayAccountType type, long amount) {
        Optional<GatewayAccount> pool = findAccount(period, type, POOL_OWNER);
        if (pool.isEmpty()) {
            return;
        }
        int n = jdbc.update("""
                UPDATE gateway_account SET available_tokens = available_tokens - ?
                WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                  AND available_tokens >= ?
                """,
                amount, company, period, type.name(), POOL_OWNER, amount);
        if (n == 0) {
            throw new GatewayQuotaInsufficientException("公司上浮池余额不足");
        }
    }

    private void debitPool(String company, String period, GatewayAccountType type, long amount) {
        ensurePoolRow(company, period, type);
        int n = jdbc.update("""
                UPDATE gateway_account SET available_tokens = available_tokens - ?
                WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                  AND available_tokens >= ?
                """,
                amount, company, period, type.name(), POOL_OWNER, amount);
        if (n == 0) {
            throw new GatewayQuotaInsufficientException("未分配池余额不足");
        }
    }

    /**
     * Idempotent insert of a zero pool row. Uses {@code ON DUPLICATE KEY UPDATE} so concurrent
     * first-touch allocate / fund paths do not 500 on duplicate PK (I-1).
     */
    private void ensurePoolRow(String company, String period, GatewayAccountType type) {
        try {
            jdbc.update("""
                    INSERT INTO gateway_account (company_id, period, account_type, owner_id, quota_mode,
                      available_tokens, reserved_tokens, settled_tokens, base_quota, float_allowance, float_turn_id)
                    VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0, 0, NULL)
                    ON DUPLICATE KEY UPDATE owner_id = owner_id
                    """,
                    company, period, type.name(), POOL_OWNER, GatewayQuotaMode.LIMITED.name());
        } catch (DuplicateKeyException ignored) {
            // Concurrent insert won the race; row exists.
        }
    }

    private void ensureEmployeeRow(String company, String period, String userId) {
        try {
            jdbc.update("""
                    INSERT INTO gateway_account (company_id, period, account_type, owner_id, quota_mode,
                      available_tokens, reserved_tokens, settled_tokens, base_quota, float_allowance, float_turn_id)
                    VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0, 0, NULL)
                    ON DUPLICATE KEY UPDATE owner_id = owner_id
                    """,
                    company, period, GatewayAccountType.EMPLOYEE.name(), userId, GatewayQuotaMode.LIMITED.name());
        } catch (DuplicateKeyException ignored) {
            // Concurrent insert won the race; row exists.
        }
    }

    /** @return true if this operation_id already completed with the same digest (idempotent replay). */
    private boolean replayOrConflict(String operationId, String digest) {
        List<String> existing = jdbc.query("""
                        SELECT param_digest FROM gateway_ledger
                        WHERE company_id = ? AND operation_id = ? AND entry_no = 1
                        """,
                (rs, n) -> rs.getString("param_digest"), TenantSchema.company(), operationId);
        if (existing.isEmpty()) {
            return false;
        }
        String prev = existing.getFirst();
        if (Objects.equals(prev, digest)) {
            return true;
        }
        throw new GatewayConflictException("operation_id 已使用且参数不同");
    }

    private long lockPoolAvailable(String company, String period, GatewayAccountType type) {
        List<Long> rows = jdbc.query("""
                SELECT available_tokens FROM gateway_account
                WHERE company_id = ? AND period = ? AND account_type = ? AND owner_id = ?
                FOR UPDATE
                """,
                (rs, n) -> rs.getLong(1), company, period, type.name(), POOL_OWNER);
        if (rows.isEmpty()) {
            throw new IllegalStateException("pool row missing after ensure: " + type);
        }
        return rows.getFirst();
    }

    private void writeLedger(String company, String operationId, int entryNo, String period,
                             GatewayAccountType accountType, String ownerId,
                             long dAvailable, long dReserved, long dSettled, String reason, String attemptId,
                             String paramDigest) {
        jdbc.update("""
                INSERT INTO gateway_ledger (company_id, operation_id, entry_no, period, account_type, owner_id,
                  delta_available, delta_reserved, delta_settled, reason, attempt_id, param_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                company, operationId, entryNo, period, accountType.name(),
                ownerId == null ? POOL_OWNER : ownerId,
                dAvailable, dReserved, dSettled, reason, attemptId, paramDigest);
    }

    private void ensureColumn(String table, String column, String type) {
        Integer present = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE LOWER(table_name) = LOWER(?) AND LOWER(column_name) = LOWER(?)",
                Integer.class, table, column);
        if (present == null || present == 0) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private GatewayAttempt requireAttempt(String company, String attemptId) {
        return findAttempt(company, attemptId)
                .orElseThrow(() -> new IllegalStateException("gateway attempt not found: " + attemptId));
    }

    /** Row-lock the attempt so concurrent settle/replay share one snapshot (H01). */
    private GatewayAttempt lockAttempt(String company, String attemptId) {
        List<GatewayAttempt> rows = jdbc.query("""
                        SELECT id, request_id, attempt_no, turn_id, run_id, payer_user_id, cli, model, period,
                          reserved_tokens, actual_tokens, status
                        FROM gateway_attempt WHERE company_id = ? AND id = ?
                        FOR UPDATE
                        """,
                ATTEMPT, company, attemptId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("gateway attempt not found: " + attemptId);
        }
        return rows.getFirst();
    }

    private GatewayAttempt settleReplayAfterRace(String company, String attemptId, long actualTokens) {
        GatewayAttempt again = requireAttempt(company, attemptId);
        if (again.status() == GatewayAttemptStatus.SETTLED) {
            if (again.actualTokens() == actualTokens) {
                return again;
            }
            throw new IllegalStateException("attempt already settled with A=" + again.actualTokens());
        }
        throw new IllegalStateException("cannot settle attempt in status " + again.status());
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

    private static void requirePeriodUser(String period, String userId) {
        if (period == null || period.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("period and userId are required");
        }
    }

    private static void requireOp(String operationId, String period, String userId, long amount) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }
        requirePeriodUser(period, userId);
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
    }

    private static String digest(String kind, String subject, long amount) {
        return kind + "|" + subject + "|" + amount;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
