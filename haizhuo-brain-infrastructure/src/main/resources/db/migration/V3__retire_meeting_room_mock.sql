-- V1/V2 已在本地数据库执行，保持历史迁移不变。
-- 只清理固定测试用户及原型操作键对应的 Run，保留通用表中的其他记录。
DELETE FROM tool_invocation_audit
WHERE run_id IN (SELECT run_id FROM agent_run
                 WHERE user_id = 1001 AND operation_key = CONCAT('run:', run_id));

DELETE FROM run_effective_capability_item
WHERE run_id IN (SELECT run_id FROM agent_run
                 WHERE user_id = 1001 AND operation_key = CONCAT('run:', run_id));

DELETE FROM run_effective_capability_set
WHERE run_id IN (SELECT run_id FROM agent_run
                 WHERE user_id = 1001 AND operation_key = CONCAT('run:', run_id));

DELETE FROM agent_run_event
WHERE run_id IN (SELECT run_id FROM agent_run
                 WHERE user_id = 1001 AND operation_key = CONCAT('run:', run_id));

DELETE FROM agent_run
WHERE user_id = 1001 AND operation_key = CONCAT('run:', run_id);

DELETE FROM agent_session
WHERE user_id = 1001
  AND NOT EXISTS (SELECT 1 FROM agent_run WHERE agent_run.session_id = agent_session.session_id);

-- 删除 V2 的会议室种子定义及其所有测试发布版本，通用定义表结构继续保留。
UPDATE digital_employee
SET current_published_version_id = NULL
WHERE id = 1 AND tenant_id = 1 AND employee_code = 'meeting-room';

DELETE FROM agent_definition_version_capability
WHERE definition_version_id IN (SELECT id FROM agent_definition_version WHERE employee_id = 1);
DELETE FROM agent_definition_version WHERE employee_id = 1;
DELETE FROM agent_definition_draft_capability WHERE employee_id = 1;
DELETE FROM agent_definition_draft WHERE employee_id = 1;
DELETE FROM agent_user_capability_grant
WHERE capability_code IN ('meeting_room.search', 'meeting_room.reserve');
DELETE FROM capability_revision
WHERE capability_code IN ('meeting_room.search', 'meeting_room.reserve');
DELETE FROM capability_definition
WHERE capability_code IN ('meeting_room.search', 'meeting_room.reserve');
DELETE FROM digital_employee
WHERE id = 1 AND tenant_id = 1 AND employee_code = 'meeting-room';

DROP TABLE IF EXISTS mock_meeting_booking;
DROP TABLE IF EXISTS mock_meeting_room;
