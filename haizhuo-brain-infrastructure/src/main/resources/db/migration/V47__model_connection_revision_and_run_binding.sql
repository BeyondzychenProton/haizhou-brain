-- Model connection metadata stores exact credential-service references only. It never stores
-- API keys, tokens, or resolved credential material.
CREATE TABLE platform_model_connection (
    connection_id VARCHAR(64) NOT NULL,
    tenant_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    connection_kind VARCHAR(32) NOT NULL DEFAULT 'MANAGED_REVISION',
    status VARCHAR(24) NOT NULL DEFAULT 'DISABLED',
    current_revision INT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (connection_id),
    UNIQUE KEY uk_model_connection_tenant_code (tenant_id, code),
    KEY idx_model_connection_status (tenant_id, status, connection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_model_connection_revision (
    connection_id VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    endpoint VARCHAR(512) NOT NULL,
    credential_reference_id VARCHAR(128) NOT NULL,
    credential_version VARCHAR(128) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (connection_id, revision),
    CONSTRAINT fk_model_connection_revision_connection FOREIGN KEY (connection_id)
        REFERENCES platform_model_connection (connection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_definition_draft_model_connection (
    employee_id BIGINT NOT NULL,
    draft_revision INT NOT NULL,
    connection_id VARCHAR(64) NOT NULL,
    connection_revision INT NOT NULL,
    connection_content_hash CHAR(64) NOT NULL,
    reference_kind VARCHAR(32) NOT NULL DEFAULT 'MANAGED_REVISION',
    PRIMARY KEY (employee_id),
    CONSTRAINT fk_agent_draft_model_connection_draft FOREIGN KEY (employee_id)
        REFERENCES agent_definition_draft (employee_id),
    CONSTRAINT fk_agent_draft_model_connection_revision FOREIGN KEY (connection_id, connection_revision)
        REFERENCES platform_model_connection_revision (connection_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_definition_version_model_connection (
    definition_version_id BIGINT NOT NULL,
    connection_id VARCHAR(64) NOT NULL,
    connection_revision INT NOT NULL,
    connection_content_hash CHAR(64) NOT NULL,
    reference_kind VARCHAR(32) NOT NULL DEFAULT 'MANAGED_REVISION',
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (definition_version_id),
    CONSTRAINT fk_agent_version_model_connection_version FOREIGN KEY (definition_version_id)
        REFERENCES agent_definition_version (id),
    CONSTRAINT fk_agent_version_model_connection_revision FOREIGN KEY (connection_id, connection_revision)
        REFERENCES platform_model_connection_revision (connection_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_run_model_connection_binding (
    run_id VARCHAR(36) NOT NULL,
    executor_role_id VARCHAR(64) NOT NULL,
    executor_definition_version_id BIGINT NOT NULL,
    connection_id VARCHAR(64) NOT NULL,
    connection_revision INT NOT NULL,
    connection_content_hash CHAR(64) NOT NULL,
    reference_kind VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (run_id, executor_role_id, executor_definition_version_id),
    KEY idx_run_model_connection_reference (connection_id, connection_revision),
    CONSTRAINT fk_run_model_connection_run FOREIGN KEY (run_id)
        REFERENCES platform_agent_run (run_id),
    CONSTRAINT fk_run_model_connection_version_binding FOREIGN KEY (executor_definition_version_id)
        REFERENCES agent_definition_version_model_connection (definition_version_id),
    CONSTRAINT fk_run_model_connection_revision FOREIGN KEY (connection_id, connection_revision)
        REFERENCES platform_model_connection_revision (connection_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
