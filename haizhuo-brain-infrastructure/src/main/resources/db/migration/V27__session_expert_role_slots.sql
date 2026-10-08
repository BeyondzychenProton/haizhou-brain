CREATE TABLE platform_session_role_slot (
    slot_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    employee_id BIGINT NOT NULL,
    definition_version_id BIGINT NOT NULL,
    harness_session_key VARCHAR(36) NOT NULL,
    workspace_runtime_key VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (slot_id),
    UNIQUE KEY uk_session_role_slot_role (session_id, role_id),
    UNIQUE KEY uk_session_role_slot_harness_key (harness_session_key),
    UNIQUE KEY uk_session_role_slot_workspace_key (workspace_runtime_key),
    KEY idx_session_role_slot_owner (session_id, user_id),
    CONSTRAINT fk_session_role_slot_session FOREIGN KEY (session_id)
        REFERENCES platform_agent_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_agent_run_execution_target (
    run_id VARCHAR(36) NOT NULL,
    execution_mode VARCHAR(32) NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    employee_id BIGINT NOT NULL,
    definition_version_id BIGINT NOT NULL,
    role_slot_id VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (run_id),
    KEY idx_run_execution_target_slot (role_slot_id),
    CONSTRAINT fk_run_execution_target_run FOREIGN KEY (run_id)
        REFERENCES platform_agent_run (run_id),
    CONSTRAINT fk_run_execution_target_role_slot FOREIGN KEY (role_slot_id)
        REFERENCES platform_session_role_slot (slot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
