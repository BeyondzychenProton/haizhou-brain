-- A system-owned seed, not a user-created employee. Administrators may later edit its draft and publish a new version.
INSERT INTO digital_employee (id, tenant_id, employee_code, display_name, enabled, row_version)
SELECT 2, 1, 'general-assistant', '通用助手', TRUE, 0
WHERE NOT EXISTS (SELECT 1 FROM digital_employee WHERE tenant_id = 1 AND employee_code = 'general-assistant');

INSERT INTO agent_definition_draft (employee_id, draft_revision, instructions, model_provider, model_name, updated_by, updated_at)
SELECT 2, 1,
       '你是海卓智慧大脑的通用助手。清晰、诚实地回答用户；没有已授权工具或可靠依据时，不虚构外部系统操作、数据或结果。',
       'dashscope', 'qwen-plus', 0, CURRENT_TIMESTAMP(3)
WHERE EXISTS (SELECT 1 FROM digital_employee WHERE id = 2 AND tenant_id = 1)
  AND NOT EXISTS (SELECT 1 FROM agent_definition_draft WHERE employee_id = 2);

INSERT INTO agent_definition_version (employee_id, version_no, instructions, model_provider, model_name, content_hash, publish_request_id, published_by, published_at)
SELECT 2, 1,
       '你是海卓智慧大脑的通用助手。清晰、诚实地回答用户；没有已授权工具或可靠依据时，不虚构外部系统操作、数据或结果。',
       'dashscope', 'qwen-plus',
       SHA2('general-assistant:v1:dashscope:qwen-plus:no-capabilities', 256),
       'system-seed-general-assistant-v1', 0, CURRENT_TIMESTAMP(3)
WHERE EXISTS (SELECT 1 FROM digital_employee WHERE id = 2 AND tenant_id = 1)
  AND NOT EXISTS (SELECT 1 FROM agent_definition_version WHERE employee_id = 2 AND version_no = 1);

UPDATE digital_employee employee
JOIN agent_definition_version version ON version.employee_id = employee.id AND version.version_no = 1
SET employee.current_published_version_id = version.id,
    employee.row_version = employee.row_version + 1
WHERE employee.id = 2
  AND employee.tenant_id = 1
  AND employee.current_published_version_id IS NULL;
