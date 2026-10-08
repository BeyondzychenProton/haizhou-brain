-- Roles describe a source type; native labels/work items identify independent instances.
ALTER TABLE platform_run_work_item
    DROP INDEX uk_run_work_item_role,
    ADD KEY idx_run_work_item_role (run_id, role_id);

ALTER TABLE platform_run_delegation_invocation
    ADD COLUMN native_session_id VARCHAR(255) NULL;
