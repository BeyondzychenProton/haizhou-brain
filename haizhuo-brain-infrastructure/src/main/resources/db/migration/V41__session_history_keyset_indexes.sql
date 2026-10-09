CREATE INDEX idx_agent_session_owner_created_id
    ON platform_agent_session(user_id, created_at, session_id);

CREATE INDEX idx_agent_session_owner_status_created_id
    ON platform_agent_session(user_id, status, created_at, session_id);

CREATE INDEX idx_agent_run_session_created_id
    ON platform_agent_run(session_id, created_at, run_id);

CREATE INDEX idx_agent_run_session_state_created_id
    ON platform_agent_run(session_id, state, created_at, run_id);

CREATE INDEX idx_agent_result_run_visibility_created_id
    ON platform_agent_result(run_id, visibility, created_at, result_id);
