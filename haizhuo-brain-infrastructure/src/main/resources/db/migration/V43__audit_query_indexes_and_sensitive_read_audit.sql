-- Global keyset scans use one timestamp/source-key order over the existing audit facts.
ALTER TABLE platform_user_audit
    ADD KEY idx_platform_user_audit_time_id (occurred_at, id);

ALTER TABLE platform_authentication_audit
    ADD KEY idx_platform_auth_audit_time_id (occurred_at, id);

ALTER TABLE agent_definition_management_audit
    ADD KEY idx_definition_audit_time_id (occurred_at, id);

ALTER TABLE capability_asset_audit
    ADD KEY idx_capability_asset_audit_time_id (occurred_at, id),
    ADD KEY idx_capability_asset_audit_actor_time_id (actor_user_id, occurred_at, id);

ALTER TABLE tool_invocation_audit
    ADD KEY idx_tool_audit_time_invocation (created_at, invocation_id),
    ADD KEY idx_tool_audit_user_created_invocation (user_id, created_at, invocation_id);

ALTER TABLE platform_run_recovery_action
    ADD KEY idx_run_recovery_audit_time_request (created_at, request_id),
    ADD KEY idx_run_recovery_audit_actor_time_request (actor_user_id, created_at, request_id);

ALTER TABLE platform_channel_management_audit
    ADD KEY idx_channel_management_audit_time_id (created_at, audit_id),
    ADD KEY idx_channel_management_audit_request_time (request_id, created_at, audit_id);

-- Detail and CSV reads are sensitive admin operations. Keep a digest of the requested target/filter,
-- not the audit body, exported rows, reason text, request payload or credentials.
CREATE TABLE platform_admin_audit_read (
    audit_read_id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NOT NULL,
    access_kind VARCHAR(16) NOT NULL,
    target_digest CHAR(64) NOT NULL,
    result_count INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (audit_read_id),
    KEY idx_platform_admin_audit_read_actor_time (actor_user_id, created_at, audit_read_id),
    CONSTRAINT fk_platform_admin_audit_read_actor FOREIGN KEY (actor_user_id) REFERENCES platform_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
