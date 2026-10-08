CREATE TABLE platform_agent_result (
    result_id VARCHAR(64) NOT NULL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    result_key CHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    attempt_id VARCHAR(64) NULL,
    invocation_id VARCHAR(64) NULL,
    employee_id BIGINT NOT NULL,
    definition_version_id BIGINT NOT NULL,
    media_type VARCHAR(64) NOT NULL,
    body LONGTEXT NOT NULL,
    body_sha256 CHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    schema_version INT NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    native_refs_json LONGTEXT NULL,
    check_metadata_json LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_agent_result_key (result_key),
    KEY idx_agent_result_run (run_id,kind),
    CONSTRAINT fk_agent_result_run FOREIGN KEY(run_id) REFERENCES platform_agent_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE platform_channel_delivery
    MODIFY COLUMN content LONGTEXT NOT NULL,
    ADD COLUMN result_id VARCHAR(64) NULL,
    ADD COLUMN sending_expires_at DATETIME(3) NULL,
    ADD KEY idx_delivery_sending_expiry (state,sending_expires_at),
    ADD CONSTRAINT fk_delivery_result FOREIGN KEY(result_id) REFERENCES platform_agent_result(result_id);
-- 存量 SENDING 缺少停止/回执证据，迁移不能推断可安全重发。
UPDATE platform_channel_delivery SET state='UNCERTAIN',last_error='MIGRATION_SENDING_UNCERTAIN' WHERE state='SENDING';
