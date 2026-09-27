-- Harness Durable Runtime (spec §55): durable enterprise tool executions with idempotency
-- keys and one-shot result delivery, plus the approval facts behind WAITING_CONFIRMATION.

CREATE TABLE platform_tool_execution (
    tool_execution_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    tool_use_id VARCHAR(128) NOT NULL,
    capability_revision_id BIGINT NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    input_json JSON NOT NULL,
    input_digest CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    idempotency_key CHAR(64) NOT NULL,
    external_receipt VARCHAR(256) NULL,
    result_json JSON NULL,
    result_digest CHAR(64) NULL,
    result_delivery_state VARCHAR(16) NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tool_execution_id),
    UNIQUE KEY uk_run_tool_use (run_id, tool_use_id),
    UNIQUE KEY uk_tool_idempotency (idempotency_key),
    KEY idx_tool_state (state, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_tool_approval (
    approval_id VARCHAR(64) NOT NULL,
    tool_execution_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    requested_for_user_id BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    decided_by_user_id BIGINT NULL,
    reason VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL,
    decided_at DATETIME(3) NULL,
    PRIMARY KEY (approval_id),
    UNIQUE KEY uk_tool_approval_execution (tool_execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
