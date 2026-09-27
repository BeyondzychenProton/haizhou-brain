-- Harness Durable Runtime (spec §52): publish-time runtime bundle + run-level frozen spec.
-- capability_revision gains a stable numeric identity referenced by bundles and tool
-- executions, plus the confirmation flag used by the approval policy (§39).

ALTER TABLE capability_revision
    ADD COLUMN capability_revision_id BIGINT NOT NULL AUTO_INCREMENT,
    ADD COLUMN requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD UNIQUE KEY uk_capability_revision_id (capability_revision_id);

CREATE TABLE agent_definition_runtime_bundle (
    id BIGINT NOT NULL AUTO_INCREMENT,
    definition_version_id BIGINT NOT NULL,
    employee_name VARCHAR(128) NOT NULL,
    model_provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    max_iterations INT NOT NULL,
    instructions MEDIUMTEXT NOT NULL,
    workspace_manifest_json JSON NOT NULL,
    workspace_content_hash CHAR(64) NOT NULL,
    tool_catalog_json JSON NOT NULL,
    tool_catalog_hash CHAR(64) NOT NULL,
    subagent_manifest_json JSON NULL,
    policy_json JSON NULL,
    bundle_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_runtime_bundle_definition (definition_version_id),
    UNIQUE KEY uk_runtime_bundle_hash (bundle_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_agent_run_spec (
    run_id VARCHAR(64) NOT NULL,
    definition_version_id BIGINT NOT NULL,
    definition_bundle_id BIGINT NOT NULL,
    definition_bundle_hash CHAR(64) NOT NULL,
    model_visible_tool_names_json JSON NOT NULL,
    tool_view_hash CHAR(64) NOT NULL,
    effective_capability_hash CHAR(64) NOT NULL,
    channel_type VARCHAR(32) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (run_id),
    KEY idx_run_spec_definition (definition_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
