-- Harness Durable Runtime (spec §54): physical execution attempts with worker lease and
-- monotonic fence token. AgentStateStore alone is not a durable engine (I-11); this table
-- is what makes claim/heartbeat/reclaim safe under multiple workers.

CREATE TABLE platform_run_execution_attempt (
    attempt_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    attempt_no INT NOT NULL,
    worker_id VARCHAR(128) NOT NULL,
    lease_token VARCHAR(64) NOT NULL,
    fence_token BIGINT NOT NULL,
    lease_expires_at DATETIME(3) NOT NULL,
    heartbeat_at DATETIME(3) NOT NULL,
    state VARCHAR(32) NOT NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (attempt_id),
    UNIQUE KEY uk_run_attempt_no (run_id, attempt_no),
    UNIQUE KEY uk_run_fence (run_id, fence_token),
    KEY idx_attempt_lease (state, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
