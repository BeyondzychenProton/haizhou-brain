-- Freeze the native tool request for idempotent delegation receipt reuse.
ALTER TABLE platform_run_delegation_invocation
    ADD COLUMN accepted_payload MEDIUMTEXT NULL;
