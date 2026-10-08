-- Work-item revisions are platform acceptance facts; AgentScope remains the child dispatcher.
ALTER TABLE platform_agent_result
    MODIFY COLUMN employee_id BIGINT NULL,
    MODIFY COLUMN definition_version_id BIGINT NULL;

CREATE TABLE platform_run_work_item (
    work_item_ref VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    employee_id BIGINT NULL,
    definition_version_id BIGINT NULL,
    definition_hash CHAR(64) NULL,
    required TINYINT(1) NOT NULL,
    latest_assignment_revision INT NOT NULL,
    state VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (work_item_ref),
    UNIQUE KEY uk_run_work_item_role (run_id, role_id),
    CONSTRAINT fk_run_work_item_run FOREIGN KEY (run_id) REFERENCES platform_agent_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_run_work_item_revision (
    work_item_ref VARCHAR(64) NOT NULL,
    assignment_revision INT NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    objective MEDIUMTEXT NOT NULL,
    deliverable_media_type VARCHAR(64) NOT NULL,
    contract_version VARCHAR(64) NOT NULL,
    required_fields_json JSON NOT NULL,
    input_refs_json JSON NOT NULL,
    dependencies_json JSON NOT NULL,
    result_id VARCHAR(64) NULL,
    result_sha256 CHAR(64) NULL,
    format_status VARCHAR(16) NOT NULL,
    review_status VARCHAR(24) NOT NULL,
    reviewed_result_id VARCHAR(64) NULL,
    review_reason VARCHAR(2000) NULL,
    created_at DATETIME(3) NOT NULL,
    reviewed_at DATETIME(3) NULL,
    PRIMARY KEY (work_item_ref, assignment_revision),
    KEY idx_work_item_revision_result (result_id),
    CONSTRAINT fk_work_item_revision_item FOREIGN KEY (work_item_ref)
        REFERENCES platform_run_work_item (work_item_ref),
    CONSTRAINT fk_work_item_revision_result FOREIGN KEY (result_id)
        REFERENCES platform_agent_result (result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE platform_run_delegation_invocation
    ADD COLUMN tool_use_id VARCHAR(128) NULL,
    ADD COLUMN work_item_ref VARCHAR(64) NULL,
    ADD COLUMN assignment_revision INT NULL,
    ADD COLUMN source_kind VARCHAR(32) NULL,
    ADD COLUMN employee_id BIGINT NULL,
    ADD COLUMN definition_version_id BIGINT NULL,
    ADD COLUMN definition_hash CHAR(64) NULL,
    ADD COLUMN input_sha256 CHAR(64) NULL,
    ADD UNIQUE KEY uk_run_delegation_tool_use (run_id, attempt_id, tool_use_id),
    ADD KEY idx_run_delegation_work_item (work_item_ref, assignment_revision),
    ADD CONSTRAINT fk_run_delegation_work_item FOREIGN KEY (work_item_ref)
        REFERENCES platform_run_work_item (work_item_ref),
    ADD CONSTRAINT fk_run_delegation_work_item_revision FOREIGN KEY (work_item_ref, assignment_revision)
        REFERENCES platform_run_work_item_revision (work_item_ref, assignment_revision);
