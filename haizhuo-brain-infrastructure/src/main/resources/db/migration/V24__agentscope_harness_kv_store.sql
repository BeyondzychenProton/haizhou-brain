-- AgentScope 2.0.3 JdbcStore schema. Runtime initialization is disabled; Flyway owns this table.
CREATE TABLE agentscope_store (
    namespace_path VARCHAR(512) NOT NULL,
    item_key VARCHAR(255) NOT NULL,
    value_json LONGTEXT NOT NULL,
    version BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (namespace_path, item_key),
    INDEX idx_namespace (namespace_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
