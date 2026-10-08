-- Preserve explicit employee runtime profiles on drafts and immutable published versions.
ALTER TABLE agent_definition_draft
    ADD COLUMN configuration_json LONGTEXT NULL;

ALTER TABLE agent_definition_version
    ADD COLUMN configuration_json LONGTEXT NULL,
    ADD UNIQUE KEY uk_agent_definition_employee_id (employee_id, id);

ALTER TABLE agent_definition_runtime_bundle
    ADD COLUMN configuration_json LONGTEXT NULL,
    ADD COLUMN workspace_files_json LONGTEXT NULL;

-- Fixed member references name an exact published version, never a moving "latest" pointer.
CREATE TABLE agent_definition_version_member (
    parent_definition_version_id BIGINT NOT NULL,
    member_role_id VARCHAR(64) NOT NULL,
    member_employee_id BIGINT NOT NULL,
    member_definition_version_id BIGINT NOT NULL,
    member_steps INT NOT NULL,
    position_no INT NOT NULL,
    PRIMARY KEY (parent_definition_version_id, member_role_id),
    UNIQUE KEY uk_agent_definition_member_position (parent_definition_version_id, position_no),
    KEY idx_agent_definition_member_version (member_definition_version_id),
    CONSTRAINT fk_agent_definition_member_parent FOREIGN KEY (parent_definition_version_id)
        REFERENCES agent_definition_version (id),
    CONSTRAINT fk_agent_definition_member_exact_version FOREIGN KEY (member_employee_id, member_definition_version_id)
        REFERENCES agent_definition_version (employee_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
