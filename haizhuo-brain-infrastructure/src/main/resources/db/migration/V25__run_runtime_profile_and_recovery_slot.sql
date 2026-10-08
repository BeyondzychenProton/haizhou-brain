-- Freeze the execution profile on each Run so recovery policy survives later employee republishes.
ALTER TABLE platform_agent_run
    ADD COLUMN runtime_profile VARCHAR(32) NOT NULL DEFAULT 'LEGACY_STABLE' AFTER state;

-- RECOVERY_REQUIRED keeps the Session execution slot occupied until an administrator records
-- external stop evidence and terminates the uncertain Run.
ALTER TABLE platform_agent_run DROP INDEX uk_platform_agent_run_session_active;
ALTER TABLE platform_agent_run DROP COLUMN active_marker;
ALTER TABLE platform_agent_run
    ADD COLUMN active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN state IN ('QUEUED','RUNNING','WAITING_CONFIRMATION','WAITING_TOOL','CANCELLING','RECOVERY_REQUIRED')
             THEN 1 ELSE NULL END) STORED;
CREATE UNIQUE INDEX uk_platform_agent_run_session_active
    ON platform_agent_run (session_id, active_marker);
