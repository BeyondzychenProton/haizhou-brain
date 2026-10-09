CREATE TABLE platform_run_feedback (
    feedback_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    score_value DOUBLE NOT NULL,
    feedback_comment VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL,
    export_disposition VARCHAR(32) NOT NULL DEFAULT 'QUEUE_STATUS_UNKNOWN',
    PRIMARY KEY (feedback_id),
    UNIQUE KEY uk_platform_run_feedback_request (user_id, run_id, client_request_id),
    KEY idx_platform_run_feedback_history (run_id, user_id, created_at, feedback_id),
    CONSTRAINT chk_platform_run_feedback_value CHECK (score_value >= 0 AND score_value <= 1),
    CONSTRAINT fk_platform_run_feedback_run FOREIGN KEY (run_id) REFERENCES platform_agent_run (run_id),
    CONSTRAINT fk_platform_run_feedback_user FOREIGN KEY (user_id) REFERENCES platform_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
