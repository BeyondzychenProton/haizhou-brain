CREATE TABLE digital_employee (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    employee_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    current_published_version_id BIGINT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_digital_employee_tenant_code (tenant_id, employee_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE capability_definition (
    capability_code VARCHAR(128) NOT NULL,
    capability_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    status_reason VARCHAR(500) NULL,
    PRIMARY KEY (capability_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE capability_revision (
    capability_code VARCHAR(128) NOT NULL,
    revision VARCHAR(32) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    implementation_key VARCHAR(64) NOT NULL,
    business_action VARCHAR(128) NOT NULL,
    input_schema_json TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    PRIMARY KEY (capability_code, revision),
    UNIQUE KEY uk_capability_revision_tool_name (tool_name),
    CONSTRAINT fk_capability_revision_definition FOREIGN KEY (capability_code)
        REFERENCES capability_definition (capability_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_definition_draft (
    employee_id BIGINT NOT NULL,
    draft_revision INT NOT NULL,
    instructions TEXT NOT NULL,
    model_provider VARCHAR(32) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (employee_id),
    CONSTRAINT fk_agent_draft_employee FOREIGN KEY (employee_id) REFERENCES digital_employee (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_definition_draft_capability (
    employee_id BIGINT NOT NULL,
    capability_code VARCHAR(128) NOT NULL,
    capability_revision VARCHAR(32) NOT NULL,
    position_no INT NOT NULL,
    PRIMARY KEY (employee_id, capability_code),
    CONSTRAINT fk_agent_draft_capability_draft FOREIGN KEY (employee_id) REFERENCES agent_definition_draft (employee_id),
    CONSTRAINT fk_agent_draft_capability_revision FOREIGN KEY (capability_code, capability_revision)
        REFERENCES capability_revision (capability_code, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_definition_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    instructions TEXT NOT NULL,
    model_provider VARCHAR(32) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    publish_request_id VARCHAR(128) NOT NULL,
    published_by BIGINT NOT NULL,
    published_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_definition_employee_version (employee_id, version_no),
    UNIQUE KEY uk_agent_definition_publish_request (employee_id, publish_request_id),
    CONSTRAINT fk_agent_definition_version_employee FOREIGN KEY (employee_id) REFERENCES digital_employee (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_definition_version_capability (
    definition_version_id BIGINT NOT NULL,
    capability_code VARCHAR(128) NOT NULL,
    capability_revision VARCHAR(32) NOT NULL,
    position_no INT NOT NULL,
    PRIMARY KEY (definition_version_id, capability_code),
    CONSTRAINT fk_agent_version_capability_version FOREIGN KEY (definition_version_id)
        REFERENCES agent_definition_version (id),
    CONSTRAINT fk_agent_version_capability_revision FOREIGN KEY (capability_code, capability_revision)
        REFERENCES capability_revision (capability_code, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_user_capability_grant (
    user_id BIGINT NOT NULL,
    capability_code VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, capability_code),
    CONSTRAINT fk_user_capability_grant_definition FOREIGN KEY (capability_code)
        REFERENCES capability_definition (capability_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE run_effective_capability_set (
    run_id VARCHAR(36) NOT NULL,
    definition_version_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    resolved_at DATETIME(3) NOT NULL,
    PRIMARY KEY (run_id),
    CONSTRAINT fk_run_capability_set_run FOREIGN KEY (run_id) REFERENCES agent_run (run_id),
    CONSTRAINT fk_run_capability_set_version FOREIGN KEY (definition_version_id) REFERENCES agent_definition_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE run_effective_capability_item (
    run_id VARCHAR(36) NOT NULL,
    capability_code VARCHAR(128) NOT NULL,
    capability_revision VARCHAR(32) NOT NULL,
    allowed BOOLEAN NOT NULL,
    reason_code VARCHAR(64) NULL,
    runtime_capability_json TEXT NULL,
    PRIMARY KEY (run_id, capability_code),
    CONSTRAINT fk_run_capability_item_set FOREIGN KEY (run_id) REFERENCES run_effective_capability_set (run_id),
    CONSTRAINT fk_run_capability_item_revision FOREIGN KEY (capability_code, capability_revision)
        REFERENCES capability_revision (capability_code, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tool_invocation_audit (
    invocation_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    capability_code VARCHAR(128) NOT NULL,
    capability_revision VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    business_action VARCHAR(128) NOT NULL,
    arguments_hash CHAR(64) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    result_status VARCHAR(24) NOT NULL,
    result_summary VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (invocation_id),
    KEY idx_tool_audit_run_created (run_id, created_at),
    CONSTRAINT fk_tool_audit_run FOREIGN KEY (run_id) REFERENCES agent_run (run_id),
    CONSTRAINT fk_tool_audit_capability_revision FOREIGN KEY (capability_code, capability_revision)
        REFERENCES capability_revision (capability_code, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO digital_employee (id, tenant_id, employee_code, display_name, enabled, row_version)
VALUES (1, 1, 'meeting-room', '会议室预定员工', TRUE, 0);

INSERT INTO capability_definition (capability_code, capability_type, status, updated_by, updated_at)
VALUES
    ('meeting_room.search', 'TOOL', 'ACTIVE', 1001, CURRENT_TIMESTAMP(3)),
    ('meeting_room.reserve', 'TOOL', 'ACTIVE', 1001, CURRENT_TIMESTAMP(3));

INSERT INTO capability_revision
    (capability_code, revision, display_name, description, tool_name, implementation_key, business_action, input_schema_json, content_hash)
VALUES
    ('meeting_room.search', '1', '查询会议室', '查询 A-201 在指定时间和人数下的空闲情况', 'meeting_room_search', 'meeting-room-v1', 'meeting_room.availability.read',
     '{"type":"object","properties":{"startAt":{"type":"string","description":"开始时间，ISO-8601 日期时间"},"endAt":{"type":"string","description":"结束时间，ISO-8601 日期时间"},"attendees":{"type":"integer","description":"参会人数"}},"required":["startAt","endAt","attendees"],"additionalProperties":false}',
     '8d01bb0c7001afce99e2c3ca8fc031a7a0ce76df0fcb9f5b5d6c4d1459020001'),
    ('meeting_room.reserve', '1', '预定会议室', '预定 A-201；必须先查询并确认可用', 'meeting_room_reserve', 'meeting-room-v1', 'meeting_room.booking.create',
     '{"type":"object","properties":{"startAt":{"type":"string","description":"开始时间，ISO-8601 日期时间"},"endAt":{"type":"string","description":"结束时间，ISO-8601 日期时间"},"attendees":{"type":"integer","description":"参会人数"}},"required":["startAt","endAt","attendees"],"additionalProperties":false}',
     'd20a9b27cb12e2788da027496dc57166de34719d1dc2f8e24d0e89f7358e0002');

UPDATE capability_revision
SET content_hash = SHA2(CONCAT_WS('|', capability_code, revision, display_name, description,
                                   tool_name, implementation_key, business_action, input_schema_json), 256);

INSERT INTO agent_definition_draft
    (employee_id, draft_revision, instructions, model_provider, model_name, updated_by, updated_at)
VALUES
    (1, 1,
     '你是会议室预定员工。本轮只处理固定会议室 A-201，时间按 Asia/Shanghai 解释。只接受明确的未来日期、开始时间、结束时间和正整数参会人数；如果缺少任何条件、时间含糊、用户要多个预定或提出当前 Mock 不支持的条件，先简洁说明缺少什么或不支持什么，不调用预定工具。条件完整时必须先查询会议室；仅当工具明确返回 available=true 且人数在容量内时，才可调用预定工具。预定结果以工具返回为准，不能声称未返回的 bookingId 或预定成功。',
     'openai', 'qwen3.7-max', 1001, CURRENT_TIMESTAMP(3));

INSERT INTO agent_definition_draft_capability (employee_id, capability_code, capability_revision, position_no)
VALUES (1, 'meeting_room.search', '1', 1), (1, 'meeting_room.reserve', '1', 2);

INSERT INTO agent_definition_version
    (id, employee_id, version_no, instructions, model_provider, model_name, content_hash, publish_request_id, published_by, published_at)
SELECT 1, employee_id, 1, instructions, model_provider, model_name,
       SHA2(CONCAT(instructions, '|', model_provider, '|', model_name,
                   '|meeting_room.reserve@1|meeting_room.search@1'), 256),
       'bootstrap-meeting-room-v1', 1001, CURRENT_TIMESTAMP(3)
FROM agent_definition_draft WHERE employee_id = 1;

INSERT INTO agent_definition_version_capability (definition_version_id, capability_code, capability_revision, position_no)
VALUES (1, 'meeting_room.search', '1', 1), (1, 'meeting_room.reserve', '1', 2);

UPDATE digital_employee SET current_published_version_id = 1 WHERE id = 1;

INSERT INTO agent_user_capability_grant (user_id, capability_code, enabled, updated_by, updated_at)
VALUES
    (1001, 'meeting_room.search', TRUE, 1001, CURRENT_TIMESTAMP(3)),
    (1001, 'meeting_room.reserve', TRUE, 1001, CURRENT_TIMESTAMP(3));
