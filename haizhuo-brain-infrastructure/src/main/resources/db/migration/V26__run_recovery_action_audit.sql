CREATE TABLE platform_run_recovery_action (
    request_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    expected_fence_token BIGINT NOT NULL,
    stop_evidence_reference VARCHAR(512) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (request_id),
    KEY idx_platform_run_recovery_action_run (run_id, created_at),
    CONSTRAINT fk_platform_run_recovery_action_run FOREIGN KEY (run_id) REFERENCES platform_agent_run (run_id),
    CONSTRAINT fk_platform_run_recovery_action_actor FOREIGN KEY (actor_user_id) REFERENCES platform_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
