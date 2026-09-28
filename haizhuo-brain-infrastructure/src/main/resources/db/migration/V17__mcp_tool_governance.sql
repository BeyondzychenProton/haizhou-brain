-- Trusted MCP connection and per-user discovery are distinct from approved tool revisions.
CREATE TABLE mcp_connection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    connection_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    endpoint_url VARCHAR(1024) NOT NULL,
    config_revision BIGINT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mcp_connection_code (connection_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mcp_discovery_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    connection_id BIGINT NOT NULL,
    connection_revision BIGINT NOT NULL,
    discovered_by BIGINT NOT NULL,
    tools_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_mcp_discovery_connection (connection_id, created_at),
    CONSTRAINT fk_mcp_discovery_connection FOREIGN KEY (connection_id) REFERENCES mcp_connection (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A model tool name may be reused by newer revisions of the same capability.
-- Cross-capability collisions are checked inside the approval transaction.
ALTER TABLE capability_revision DROP INDEX uk_capability_revision_tool_name;

CREATE TABLE mcp_tool_revision (
    capability_revision_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_revision BIGINT NOT NULL,
    remote_tool_name VARCHAR(128) NOT NULL,
    output_schema_json JSON NULL,
    read_only BOOLEAN NOT NULL,
    approved_by BIGINT NOT NULL,
    approved_at DATETIME(3) NOT NULL,
    PRIMARY KEY (capability_revision_id),
    KEY idx_mcp_tool_connection (connection_id, remote_tool_name),
    CONSTRAINT fk_mcp_tool_capability_revision FOREIGN KEY (capability_revision_id)
        REFERENCES capability_revision (capability_revision_id),
    CONSTRAINT fk_mcp_tool_connection FOREIGN KEY (connection_id) REFERENCES mcp_connection (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
