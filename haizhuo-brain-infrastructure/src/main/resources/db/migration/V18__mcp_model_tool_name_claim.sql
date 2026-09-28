-- Preserve one stable model-visible name across revisions of the same capability,
-- while preventing concurrent administrators from claiming it for different capabilities.
CREATE TABLE mcp_model_tool_name_claim (
    tool_name VARCHAR(128) NOT NULL,
    capability_code VARCHAR(128) NOT NULL,
    PRIMARY KEY (tool_name),
    KEY idx_mcp_name_claim_capability (capability_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A cross-capability duplicate in existing data intentionally fails this migration.
INSERT INTO mcp_model_tool_name_claim (tool_name, capability_code)
SELECT DISTINCT tool_name, capability_code FROM capability_revision;
