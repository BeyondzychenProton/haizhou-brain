-- Keep V1's retired meeting-room records untouched. New tables carry the post-authentication business model.
CREATE TABLE platform_agent_session (
    session_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    last_active_at DATETIME(3) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id),
    KEY idx_platform_agent_session_owner_active (user_id, status, last_active_at),
    CONSTRAINT fk_platform_agent_session_user FOREIGN KEY (user_id) REFERENCES platform_user (id),
    CONSTRAINT fk_platform_agent_session_employee FOREIGN KEY (employee_id) REFERENCES digital_employee (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_agent_run (
    run_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    definition_version_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    input_digest CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN state IN ('QUEUED','RUNNING','WAITING_CONFIRMATION','CANCELLING') THEN 1 ELSE NULL END) STORED,
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    failure_code VARCHAR(64) NULL,
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_platform_agent_run_owner_request (user_id, client_request_id),
    UNIQUE KEY uk_platform_agent_run_session_active (session_id, active_marker),
    KEY idx_platform_agent_run_owner_created (user_id, created_at),
    KEY idx_platform_agent_run_state_created (state, created_at),
    CONSTRAINT fk_platform_agent_run_session FOREIGN KEY (session_id) REFERENCES platform_agent_session (session_id),
    CONSTRAINT fk_platform_agent_run_user FOREIGN KEY (user_id) REFERENCES platform_user (id),
    CONSTRAINT fk_platform_agent_run_employee FOREIGN KEY (employee_id) REFERENCES digital_employee (id),
    CONSTRAINT fk_platform_agent_run_definition FOREIGN KEY (definition_version_id) REFERENCES agent_definition_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_agent_run_event (
    run_id VARCHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (run_id, sequence_no),
    CONSTRAINT fk_platform_agent_run_event_run FOREIGN KEY (run_id) REFERENCES platform_agent_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
