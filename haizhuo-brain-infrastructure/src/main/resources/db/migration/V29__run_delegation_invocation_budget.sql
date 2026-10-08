-- Child invocation count and active reservations are Run facts, not attempt-local memory.
CREATE TABLE platform_run_delegation_invocation (
    invocation_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    fence_token BIGINT NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (invocation_id),
    KEY idx_run_delegation_budget (run_id, state),
    KEY idx_run_delegation_attempt (attempt_id, fence_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
