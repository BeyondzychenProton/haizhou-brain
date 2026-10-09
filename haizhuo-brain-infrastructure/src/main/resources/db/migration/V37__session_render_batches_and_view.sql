CREATE TABLE platform_session_render_state (
    session_id VARCHAR(36) NOT NULL,
    next_render_cursor BIGINT NOT NULL DEFAULT 1,
    committed_render_cursor BIGINT NOT NULL DEFAULT 0,
    render_cursor_floor BIGINT NOT NULL DEFAULT 0,
    draft_recovery_status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE',
    degraded_reason_code VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT fk_session_render_state_session FOREIGN KEY (session_id)
        REFERENCES platform_agent_session(session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_session_render_attempt (
    session_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    fence_token BIGINT NOT NULL,
    last_text_offset BIGINT NOT NULL DEFAULT 0,
    phase VARCHAR(24) NOT NULL DEFAULT 'GENERATING',
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (session_id, run_id, attempt_id),
    KEY idx_render_attempt_run (run_id, attempt_id, fence_token),
    CONSTRAINT fk_render_attempt_session FOREIGN KEY (session_id)
        REFERENCES platform_agent_session(session_id),
    CONSTRAINT fk_render_attempt_run FOREIGN KEY (run_id)
        REFERENCES platform_agent_run(run_id),
    CONSTRAINT fk_render_attempt_native FOREIGN KEY (attempt_id)
        REFERENCES platform_run_execution_attempt(attempt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_session_message (
    message_id VARCHAR(191) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    executor_role_id VARCHAR(64) NOT NULL,
    identity_quality VARCHAR(16) NOT NULL,
    message_ordinal BIGINT NOT NULL,
    phase VARCHAR(24) NOT NULL,
    result_id VARCHAR(64) NULL,
    last_render_cursor BIGINT NOT NULL,
    partial TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (message_id),
    UNIQUE KEY uk_session_message_ordinal (session_id, message_ordinal),
    KEY idx_session_message_run (session_id, run_id, attempt_id, kind, message_ordinal),
    CONSTRAINT fk_session_message_session FOREIGN KEY (session_id)
        REFERENCES platform_agent_session(session_id),
    CONSTRAINT fk_session_message_run FOREIGN KEY (run_id)
        REFERENCES platform_agent_run(run_id),
    CONSTRAINT fk_session_message_attempt FOREIGN KEY (attempt_id)
        REFERENCES platform_run_execution_attempt(attempt_id),
    CONSTRAINT fk_session_message_result FOREIGN KEY (result_id)
        REFERENCES platform_agent_result(result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_session_message_block (
    message_id VARCHAR(191) NOT NULL,
    block_id VARCHAR(96) NOT NULL,
    block_type VARCHAR(24) NOT NULL,
    block_ordinal BIGINT NOT NULL,
    safe_body LONGTEXT NOT NULL,
    last_applied_offset BIGINT NOT NULL,
    byte_size BIGINT NOT NULL,
    body_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (message_id, block_id),
    CONSTRAINT fk_session_message_block_message FOREIGN KEY (message_id)
        REFERENCES platform_session_message(message_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_session_render_batch (
    session_id VARCHAR(36) NOT NULL,
    render_cursor BIGINT NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    fence_token BIGINT NOT NULL,
    stream_offset BIGINT NOT NULL,
    from_offset BIGINT NOT NULL,
    to_offset BIGINT NOT NULL,
    message_id VARCHAR(191) NOT NULL,
    block_id VARCHAR(96) NOT NULL,
    safe_segments_json LONGTEXT NOT NULL,
    safe_delta LONGTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    committed_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (session_id, render_cursor),
    UNIQUE KEY uk_session_render_range (session_id, run_id, attempt_id, from_offset),
    KEY idx_session_render_expiry (session_id, expires_at, render_cursor),
    CONSTRAINT fk_session_render_batch_session FOREIGN KEY (session_id)
        REFERENCES platform_agent_session(session_id),
    CONSTRAINT fk_session_render_batch_run FOREIGN KEY (run_id)
        REFERENCES platform_agent_run(run_id),
    CONSTRAINT fk_session_render_batch_attempt FOREIGN KEY (attempt_id)
        REFERENCES platform_run_execution_attempt(attempt_id),
    CONSTRAINT fk_session_render_batch_message_block FOREIGN KEY (message_id, block_id)
        REFERENCES platform_session_message_block(message_id, block_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO platform_session_render_state(session_id,next_render_cursor,committed_render_cursor,
        render_cursor_floor,draft_recovery_status,updated_at)
SELECT session_id,1,0,0,'AVAILABLE',CURRENT_TIMESTAMP(3) FROM platform_agent_session;
