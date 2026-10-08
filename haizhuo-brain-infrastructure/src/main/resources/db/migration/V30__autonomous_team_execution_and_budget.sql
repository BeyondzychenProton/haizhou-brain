-- One durable mapping per parent Run. A new parent Run receives a new native Team generation.
CREATE TABLE platform_run_team_execution (
    team_execution_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    fence_token BIGINT NOT NULL,
    team_name VARCHAR(96) NOT NULL,
    native_namespace VARCHAR(128) NOT NULL,
    state VARCHAR(24) NOT NULL,
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    result_sha256 CHAR(64) NULL,
    recovery_reason_code VARCHAR(64) NULL,
    PRIMARY KEY (team_execution_id),
    UNIQUE KEY uk_run_team_execution_run (run_id),
    UNIQUE KEY uk_run_team_execution_name (team_name),
    KEY idx_run_team_execution_owner (user_id, session_id, started_at),
    CONSTRAINT fk_run_team_execution_run FOREIGN KEY (run_id) REFERENCES platform_agent_run (run_id),
    CONSTRAINT fk_run_team_execution_user FOREIGN KEY (user_id) REFERENCES platform_user (id),
    CONSTRAINT fk_run_team_execution_session FOREIGN KEY (session_id) REFERENCES platform_agent_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_run_team_member_binding (
    team_execution_id VARCHAR(64) NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    employee_id BIGINT NOT NULL,
    definition_version_id BIGINT NOT NULL,
    harness_session_key VARCHAR(160) NOT NULL,
    member_kind VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    PRIMARY KEY (team_execution_id, role_id),
    UNIQUE KEY uk_run_team_member_session (team_execution_id, harness_session_key),
    CONSTRAINT fk_run_team_member_execution FOREIGN KEY (team_execution_id)
        REFERENCES platform_run_team_execution (team_execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_run_team_action_reservation (
    action_id VARCHAR(36) NOT NULL,
    team_execution_id VARCHAR(64) NOT NULL,
    action_type VARCHAR(24) NOT NULL,
    action_count INT NOT NULL,
    reserved_at DATETIME(3) NOT NULL,
    PRIMARY KEY (action_id),
    KEY idx_run_team_action_budget (team_execution_id, action_type),
    CONSTRAINT fk_run_team_action_execution FOREIGN KEY (team_execution_id)
        REFERENCES platform_run_team_execution (team_execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
