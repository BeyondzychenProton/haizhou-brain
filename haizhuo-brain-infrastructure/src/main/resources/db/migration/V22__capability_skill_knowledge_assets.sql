-- SKILL/KNOWLEDGE 采用同一 capability revision 身份，不伪造工具执行字段。
ALTER TABLE capability_revision
    MODIFY COLUMN tool_name VARCHAR(128) NULL,
    MODIFY COLUMN implementation_key VARCHAR(64) NULL,
    MODIFY COLUMN business_action VARCHAR(128) NULL;

CREATE TABLE capability_asset_draft (
    capability_code VARCHAR(128) NOT NULL,
    capability_type VARCHAR(24) NOT NULL,
    draft_revision INT NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    files_json LONGTEXT NOT NULL,
    manifest_json LONGTEXT NOT NULL,
    asset_hash CHAR(64) NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (capability_code),
    CONSTRAINT fk_capability_asset_draft_definition FOREIGN KEY (capability_code)
        REFERENCES capability_definition (capability_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE capability_asset_revision (
    capability_revision_id BIGINT NOT NULL,
    capability_code VARCHAR(128) NOT NULL,
    revision VARCHAR(32) NOT NULL,
    capability_type VARCHAR(24) NOT NULL,
    manifest_json LONGTEXT NOT NULL,
    asset_hash CHAR(64) NOT NULL,
    draft_revision INT NOT NULL,
    publish_request_id VARCHAR(128) NOT NULL,
    reviewed_by BIGINT NOT NULL,
    reviewed_at DATETIME(3) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (capability_revision_id),
    UNIQUE KEY uk_capability_asset_revision_code (capability_code, revision),
    UNIQUE KEY uk_capability_asset_publish_request (capability_code, publish_request_id),
    CONSTRAINT fk_capability_asset_revision_catalog FOREIGN KEY (capability_revision_id)
        REFERENCES capability_revision (capability_revision_id),
    CONSTRAINT fk_capability_asset_revision_definition FOREIGN KEY (capability_code)
        REFERENCES capability_definition (capability_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE capability_asset_file (
    capability_revision_id BIGINT NOT NULL,
    relative_path VARCHAR(512) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    content LONGTEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    byte_size INT NOT NULL,
    PRIMARY KEY (capability_revision_id, relative_path),
    CONSTRAINT fk_capability_asset_file_revision FOREIGN KEY (capability_revision_id)
        REFERENCES capability_asset_revision (capability_revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE capability_asset_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    capability_code VARCHAR(128) NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    request_id VARCHAR(128) NULL,
    reason VARCHAR(500) NOT NULL,
    previous_hash CHAR(64) NULL,
    new_hash CHAR(64) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_capability_asset_audit_code_time (capability_code, occurred_at),
    KEY idx_capability_asset_audit_request (request_id),
    CONSTRAINT fk_capability_asset_audit_definition FOREIGN KEY (capability_code)
        REFERENCES capability_definition (capability_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
