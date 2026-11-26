package com.superagent.storage.jdbc;

import com.superagent.core.usage.UsageRecord;
import com.superagent.core.usage.UsageStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Token usage on MySQL, or on ClickHouse as analytics data: rows are only appended, so the same SQL serves both. */
public class JdbcUsageStore implements UsageStore {

    static final String MYSQL_DDL = """
            CREATE TABLE IF NOT EXISTS token_usage (
              company_id     VARCHAR(64)  NOT NULL,
              id             VARCHAR(64)  NOT NULL,
              at             DATETIME(3)  NOT NULL,
              user_name      VARCHAR(128),
              source         VARCHAR(32)  NOT NULL,
              agent_id       VARCHAR(64),
              task_id        VARCHAR(64),
              session_id     VARCHAR(64),
              run_id         VARCHAR(64),
              cli            VARCHAR(32),
              model          VARCHAR(128),
              input_tokens   BIGINT NOT NULL,
              cache_read     BIGINT NOT NULL,
              cache_write    BIGINT NOT NULL,
              cache_write_1h BIGINT NOT NULL,
              output_tokens  BIGINT NOT NULL,
              reasoning      BIGINT NOT NULL,
              context_tokens BIGINT,
              cost_usd       DOUBLE,
              cli_cost_usd   DOUBLE,
              PRIMARY KEY (company_id, id),
              KEY idx_usage_task (company_id, task_id),
              KEY idx_usage_user_at (company_id, user_name, at),
              KEY idx_usage_at (company_id, at),
              KEY idx_usage_run (company_id, run_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    static final String CLICKHOUSE_DDL = """
            CREATE TABLE IF NOT EXISTS token_usage (
              company_id String, id String, at DateTime64(3, 'UTC'), user_name Nullable(String), source String, agent_id Nullable(String),
              task_id Nullable(String), session_id Nullable(String), run_id Nullable(String), cli Nullable(String), model Nullable(String),
              input_tokens Int64, cache_read Int64, cache_write Int64, cache_write_1h Int64, output_tokens Int64,
              reasoning Int64, context_tokens Nullable(Int64), cost_usd Nullable(Float64), cli_cost_usd Nullable(Float64)
            ) ENGINE = MergeTree ORDER BY (company_id, at, id)
            """;

    private static final String COLUMNS = "id, at, user_name, source, agent_id, task_id, session_id, run_id, cli, model, input_tokens, "
            + "cache_read, cache_write, cache_write_1h, output_tokens, reasoning, context_tokens, cost_usd, cli_cost_usd";

    private static final RowMapper<UsageRecord> MAPPER = (rs, i) -> new UsageRecord(rs.getString(1),
            rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getString(7), rs.getString(9), rs.getString(10), rs.getLong(11), rs.getLong(12), rs.getLong(13),
            rs.getLong(14), rs.getLong(15), rs.getLong(16), rs.getLong(17), (Double) rs.getObject(18, Double.class),
            (Double) rs.getObject(19, Double.class), rs.getString(8));

    private final JdbcTemplate jdbc;

    public JdbcUsageStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, ddl);
        boolean clickHouse = ddl.contains("MergeTree");
        ensureContextColumn(clickHouse);
        ensureRunIdColumn(clickHouse);
    }

    /**
     * Adds a column missing from older tables. Uses information_schema when possible; if two instances race on
     * ALTER, a duplicate-column error is treated as success after re-checking.
     */
    private void ensureContextColumn(boolean clickHouse) {
        ensureOptionalColumn("context_tokens", "BIGINT", "Nullable(Int64)", clickHouse);
    }

    private void ensureRunIdColumn(boolean clickHouse) {
        ensureOptionalColumn("run_id", "VARCHAR(64)", "Nullable(String)", clickHouse);
    }

    private void ensureOptionalColumn(String column, String mysqlType, String clickHouseType, boolean clickHouse) {
        if (columnPresent(column, clickHouse)) {
            return;
        }
        try {
            jdbc.execute("ALTER TABLE token_usage ADD COLUMN " + column + " "
                    + (clickHouse ? clickHouseType : mysqlType));
        } catch (org.springframework.dao.DataAccessException e) {
            // Concurrent first-boot: the other instance won the race.
            if (columnPresent(column, clickHouse)) {
                return;
            }
            throw e;
        }
    }

    private boolean columnPresent(String column, boolean clickHouse) {
        if (!clickHouse) {
            try {
                // Scope to the current schema so another DB's token_usage cannot fool us (r8).
                Integer present = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns "
                                + "WHERE LOWER(table_schema) = LOWER(DATABASE()) "
                                + "AND LOWER(table_name) = 'token_usage' AND LOWER(column_name) = LOWER(?)",
                        Integer.class, column);
                if (present != null && present > 0) {
                    return true;
                }
            } catch (org.springframework.dao.DataAccessException ignored) {
                // Fall through to SELECT probe (H2 / drivers where DATABASE() ≠ table_schema).
            }
        }
        // Authoritative for this connection: works when information_schema schema filter misses (H2).
        try {
            jdbc.queryForList("SELECT " + column + " FROM token_usage WHERE 1 = 0");
            return true;
        } catch (org.springframework.dao.DataAccessException missing) {
            return false;
        }
    }

    @Override
    public void save(UsageRecord r) {
        jdbc.update("INSERT INTO token_usage (company_id, " + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), r.id(), utc(r.at()), r.user(), r.source(), r.agentId(), r.taskId(), r.sessionId(),
                r.runId(), r.cli(), r.model(), r.input(), r.cacheRead(), r.cacheWrite(), r.cacheWrite1h(), r.output(),
                r.reasoning(), r.contextTokens(), r.costUsd(), r.cliCostUsd());
    }

    @Override
    public UsageRecord latestChatTurn(String sessionId) {
        List<UsageRecord> found = jdbc.query("SELECT " + COLUMNS + " FROM token_usage WHERE company_id = ? AND session_id = ? "
                + "AND source IN ('chat', 'superagent') ORDER BY at DESC LIMIT 1", MAPPER, TenantSchema.company(), sessionId);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public List<UsageRecord> forTask(String taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM token_usage WHERE company_id = ? AND task_id = ? ORDER BY at", MAPPER,
                TenantSchema.company(), taskId);
    }

    @Override
    public List<UsageRecord> between(String user, Instant from, Instant to) {
        List<Object> args = new ArrayList<>(List.of(TenantSchema.company(), utc(from), utc(to)));
        String byUser = "";
        if (user != null) {
            byUser = " AND user_name = ?";
            args.add(user);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM token_usage WHERE company_id = ? AND at >= ? AND at < ?" + byUser + " ORDER BY at",
                MAPPER, args.toArray());
    }

    /** Times as UTC wall-clock values: MySQL (UTC connection) and ClickHouse both bind these as proper datetimes. */
    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
