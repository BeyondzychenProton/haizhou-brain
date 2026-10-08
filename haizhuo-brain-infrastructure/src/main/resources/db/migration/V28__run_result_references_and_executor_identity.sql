ALTER TABLE platform_agent_result
    ADD COLUMN executor_role_id VARCHAR(64) NULL;

CREATE TABLE platform_agent_run_result_reference (
    run_id VARCHAR(36) NOT NULL,
    reference_order SMALLINT NOT NULL,
    result_id VARCHAR(64) NOT NULL,
    source_run_id VARCHAR(36) NOT NULL,
    body_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (run_id, reference_order),
    UNIQUE KEY uk_run_result_reference_result (run_id, result_id),
    KEY idx_run_result_reference_source (source_run_id, result_id),
    CONSTRAINT fk_run_result_reference_run FOREIGN KEY (run_id)
        REFERENCES platform_agent_run (run_id),
    CONSTRAINT fk_run_result_reference_result FOREIGN KEY (result_id)
        REFERENCES platform_agent_result (result_id),
    CONSTRAINT fk_run_result_reference_source_run FOREIGN KEY (source_run_id)
        REFERENCES platform_agent_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
