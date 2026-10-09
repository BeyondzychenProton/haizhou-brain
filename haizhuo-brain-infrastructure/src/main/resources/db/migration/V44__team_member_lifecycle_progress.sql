-- Record only ordered lifecycle state from AgentScope member events; BOUND remains UNKNOWN to users.
ALTER TABLE platform_run_team_member_binding
    ADD COLUMN last_transition_ordinal BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_transition_phase TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN last_transition_at DATETIME(3) NULL;
