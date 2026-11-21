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

    /** Pre-project_id schema: used in upgrade tests; production always uses {@link #MYSQL_DDL}. */
    static final String MYSQL_DDL_BEFORE_PROJECT = """
            CREATE TABLE IF NOT EXISTS token_usage (
              company_id     VARCHAR(64)  NOT NULL,
              id             VARCHAR(64)  NOT NULL,
              at             DATETIME(3)  NOT NULL,
              user_name      VARCHAR(128),
              source         VARCHAR(32)  NOT NULL,
              agent_id       VARCHAR(64),
              task_id        VARCHAR(64),
              session_id     VARCHAR(64),
              cli            VARCHAR(32),
              model          VARCHAR(128),
              input_tokens   BIGINT NOT NULL,
              cache_read     BIGINT NOT NULL,
              cache_write    BIGINT NOT NULL,
              cache_write_1h BIGINT NOT NULL,
              output_tokens  BIGINT NOT NULL,
              reasoning      BIGINT NOT NULL,
              cost_usd       DOUBLE,
              cli_cost_usd   DOUBLE,
              PRIMARY KEY (company_id, id),
              KEY idx_usage_task (company_id, task_id),
              KEY idx_usage_user_at (company_id, user_name, at),
              KEY idx_usage_at (company_id, at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    static final String MYSQL_DDL = """
            CREATE TABLE IF NOT EXISTS token_usage (
              company_id     VARCHAR(64)  NOT NULL,
              id             VARCHAR(64)  NOT NULL,
              at             DATETIME(3)  NOT NULL,
              user_name      VARCHAR(128),
              source         VARCHAR(32)  NOT NULL,
              agent_id       VARCHAR(64),
              task_id        VARCHAR(64),
              project_id     VARCHAR(64),
              session_id     VARCHAR(64),
              cli            VARCHAR(32),
              model          VARCHAR(128),
              input_tokens   BIGINT NOT NULL,
              cache_read     BIGINT NOT NULL,
              cache_write    BIGINT NOT NULL,
              cache_write_1h BIGINT NOT NULL,
              output_tokens  BIGINT NOT NULL,
              reasoning      BIGINT NOT NULL,
              cost_usd       DOUBLE,
              cli_cost_usd   DOUBLE,
              PRIMARY KEY (company_id, id),
              KEY idx_usage_task (company_id, task_id),
              KEY idx_usage_project_at (company_id, project_id, at),
              KEY idx_usage_user_at (company_id, user_name, at),
              KEY idx_usage_at (company_id, at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    static final String CLICKHOUSE_DDL = """
            CREATE TABLE IF NOT EXISTS token_usage (
              company_id String, id String, at DateTime64(3, 'UTC'), user_name Nullable(String), source String, agent_id Nullable(String),
              task_id Nullable(String), project_id Nullable(String), session_id Nullable(String), cli Nullable(String), model Nullable(String),
              input_tokens Int64, cache_read Int64, cache_write Int64, cache_write_1h Int64, output_tokens Int64,
              reasoning Int64, cost_usd Nullable(Float64), cli_cost_usd Nullable(Float64)
            ) ENGINE = MergeTree ORDER BY (company_id, at, id)
            """;

    private static final String COLUMNS = "id, at, user_name, source, agent_id, task_id, project_id, session_id, cli, model, "
            + "input_tokens, cache_read, cache_write, cache_write_1h, output_tokens, reasoning, cost_usd, cli_cost_usd";

    private static final RowMapper<UsageRecord> MAPPER = (rs, i) -> new UsageRecord(rs.getString(1),
            rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getLong(11),
            rs.getLong(12), rs.getLong(13), rs.getLong(14), rs.getLong(15), rs.getLong(16),
            (Double) rs.getObject(17, Double.class), (Double) rs.getObject(18, Double.class));

    private final JdbcTemplate jdbc;

    public JdbcUsageStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, ddl);
        ensureProjectId(ddl);
    }

    /**
     * Adds {@code project_id} (and its MySQL index) when upgrading an existing table.
     * Only "already exists" outcomes are ignored; permission / syntax / other failures abort startup.
     */
    private void ensureProjectId(String ddl) {
        if (ddl == MYSQL_DDL || ddl == MYSQL_DDL_BEFORE_PROJECT) {
            // CREATE IF NOT EXISTS leaves old tables unchanged — migrate those explicitly.
            ensureMysqlColumn("project_id", "VARCHAR(64)");
            ensureMysqlIndex("idx_usage_project_at", "(company_id, project_id, at)");
            return;
        }
        // ClickHouse: IF NOT EXISTS is idempotent; other errors (permissions, etc.) must surface.
        jdbc.execute("ALTER TABLE token_usage ADD COLUMN IF NOT EXISTS project_id Nullable(String)");
    }

    private void ensureMysqlColumn(String column, String type) {
        if (mysqlObjectPresent(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                        + "AND LOWER(table_name) = 'token_usage' AND LOWER(column_name) = LOWER(?)",
                column)) {
            return;
        }
        try {
            jdbc.execute("ALTER TABLE token_usage ADD COLUMN " + column + " " + type);
        } catch (RuntimeException e) {
            if (alreadyExists(e)) {
                return;
            }
            throw e;
        }
    }

    private void ensureMysqlIndex(String name, String columns) {
        if (mysqlObjectPresent(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = SCHEMA() "
                        + "AND LOWER(table_name) = 'token_usage' AND LOWER(index_name) = LOWER(?)",
                name)) {
            return;
        }
        try {
            jdbc.execute("ALTER TABLE token_usage ADD KEY " + name + " " + columns);
        } catch (RuntimeException e) {
            if (alreadyExists(e)) {
                return;
            }
            throw e;
        }
    }

    /** {@code true} when the catalog says the object is there; {@code false} if absent or the catalog query is unavailable (e.g. H2). */
    private boolean mysqlObjectPresent(String sql, String name) {
        try {
            Integer present = jdbc.queryForObject(sql, Integer.class, name);
            return present != null && present > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** MySQL 1060/1061 and common "already exists" messages from H2 / drivers. */
    static boolean alreadyExists(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql) {
                int code = sql.getErrorCode();
                if (code == 1060 || code == 1061) { // Duplicate column name / Duplicate key name
                    return true;
                }
            }
            String msg = t.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(java.util.Locale.ROOT);
                if (m.contains("duplicate column") || m.contains("duplicate key")
                        || m.contains("already exists") || m.contains("index collides")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void save(UsageRecord r) {
        jdbc.update("INSERT INTO token_usage (company_id, " + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), r.id(), utc(r.at()), r.user(), r.source(), r.agentId(), r.taskId(), r.projectId(),
                r.sessionId(), r.cli(), r.model(), r.input(), r.cacheRead(), r.cacheWrite(), r.cacheWrite1h(), r.output(),
                r.reasoning(), r.costUsd(), r.cliCostUsd());
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
