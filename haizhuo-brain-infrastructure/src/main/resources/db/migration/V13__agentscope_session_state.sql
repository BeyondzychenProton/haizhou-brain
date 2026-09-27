-- Harness Durable Runtime (spec §22): AgentScope session state table.
--
-- JdbcAgentStateStore(MysqlDialect) verifies this table in its constructor and refuses to
-- build the run-worker bean graph when it is missing, so the table must exist before
-- AgentRuntimeConfiguration.agentStateStore(..) runs. Column names and types mirror
-- io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect#sessionStateCreateTableDdls()
-- (AgentScope Java 2.0.3); only charset/collation follows this project's convention
-- (utf8mb4_0900_ai_ci) instead of the dialect's utf8mb4_unicode_ci.
--
-- agentscope_store / agentscope_snapshots are intentionally not created here: the remote
-- workspace and snapshot planes stay unwired until AgentScope ships a durable BaseStore
-- (documented P0 gap), so no table exists for them yet.

CREATE TABLE IF NOT EXISTS agentscope_sessions (
    session_id VARCHAR(255) NOT NULL,
    state_key VARCHAR(255) NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index),
    KEY idx_agentscope_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
