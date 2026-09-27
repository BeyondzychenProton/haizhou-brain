CREATE TABLE agent_definition_management_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NULL,
    reason VARCHAR(500) NOT NULL,
    previous_summary TEXT NOT NULL,
    new_summary TEXT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_definition_management_audit_actor_time (actor_user_id, occurred_at),
    KEY idx_agent_definition_management_audit_target_time (target_type, target_id, occurred_at),
    KEY idx_agent_definition_management_audit_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
