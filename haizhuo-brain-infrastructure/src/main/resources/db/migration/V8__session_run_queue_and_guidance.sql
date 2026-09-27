-- Allow several queued Runs for one Session; the worker keeps one Run running per Session.
-- The existing unique index is also InnoDB's backing index for fk_platform_agent_run_session.
-- Add the replacement first, otherwise MySQL rejects dropping the unique index.
CREATE INDEX idx_platform_agent_run_session_state_created ON platform_agent_run(session_id, state, created_at);
ALTER TABLE platform_agent_run DROP INDEX uk_platform_agent_run_session_active;
ALTER TABLE platform_agent_run ADD COLUMN cancel_requested_at DATETIME(3) NULL AFTER started_at;

CREATE TABLE platform_agent_run_guidance (
    guidance_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    author_user_id BIGINT NOT NULL,
    source VARCHAR(24) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    PRIMARY KEY (guidance_id),
    KEY idx_platform_agent_run_guidance_pending (run_id, status, created_at),
    CONSTRAINT fk_platform_agent_run_guidance_run FOREIGN KEY (run_id) REFERENCES platform_agent_run (run_id),
    CONSTRAINT fk_platform_agent_run_guidance_author FOREIGN KEY (author_user_id) REFERENCES platform_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
