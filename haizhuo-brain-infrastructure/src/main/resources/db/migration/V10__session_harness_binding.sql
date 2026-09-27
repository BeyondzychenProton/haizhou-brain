-- Harness Durable Runtime (spec §53/§56): platform-session → Harness binding + one-shot
-- bridge snapshot, and WAITING_TOOL joins the session-slot-holding states.

CREATE TABLE platform_session_bridge_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    definition_version_id BIGINT NOT NULL,
    generation INT NOT NULL,
    source_event_sequence INT NOT NULL,
    conversation_summary MEDIUMTEXT NOT NULL,
    stable_business_facts_json JSON NOT NULL,
    schema_version INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bridge_scope (user_id, session_id, definition_version_id, generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_session_harness_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    employee_id BIGINT NOT NULL,
    definition_version_id BIGINT NOT NULL,
    generation INT NOT NULL,
    harness_session_key VARCHAR(96) NOT NULL,
    workspace_runtime_key VARCHAR(96) NOT NULL,
    bridge_snapshot_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_harness_binding_scope (user_id, session_id, definition_version_id, generation),
    UNIQUE KEY uk_harness_session_key (harness_session_key),
    UNIQUE KEY uk_workspace_runtime_key (workspace_runtime_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- WAITING_TOOL (spec §12.1) occupies the session ordering slot exactly like the other
-- active states. The generated column is redefined by drop + re-add so the unique
-- active-slot index keeps working on both MySQL and H2 test schemas.
ALTER TABLE platform_agent_run DROP COLUMN active_marker;
ALTER TABLE platform_agent_run
    ADD COLUMN active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN state IN ('QUEUED','RUNNING','WAITING_CONFIRMATION','WAITING_TOOL','CANCELLING')
             THEN 1 ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_platform_agent_run_session_active (session_id, active_marker);
