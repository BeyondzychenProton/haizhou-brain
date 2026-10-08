-- 升级窗口停止旧 Worker；保留既有 SSE 编号。缺失旧投影追加高位游标，不假称找回被裁剪的编号。
ALTER TABLE platform_agent_session ADD COLUMN next_event_cursor BIGINT NOT NULL DEFAULT 1;
ALTER TABLE platform_agent_run_event
    ADD COLUMN session_cursor BIGINT NULL,
    ADD COLUMN schema_version INT NOT NULL DEFAULT 1,
    ADD COLUMN event_id VARCHAR(191) NULL,
    ADD COLUMN business_key CHAR(64) NULL,
    ADD COLUMN visibility VARCHAR(32) NOT NULL DEFAULT 'USER',
    ADD COLUMN attempt_id VARCHAR(64) NULL,
    ADD COLUMN fence_token BIGINT NULL,
    ADD COLUMN origin_kind VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
    ADD COLUMN native_refs_json LONGTEXT NULL,
    ADD COLUMN payload_json LONGTEXT NULL,
    ADD COLUMN result_id VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_run_event_business (run_id,business_key),
    ADD UNIQUE KEY uk_run_event_id (event_id);
ALTER TABLE platform_agent_session_event
    ADD COLUMN schema_version INT NOT NULL DEFAULT 1,
    ADD COLUMN event_id VARCHAR(191) NULL,
    ADD COLUMN attempt_id VARCHAR(64) NULL,
    ADD COLUMN fence_token BIGINT NULL,
    ADD COLUMN origin_kind VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
    ADD COLUMN native_refs_json LONGTEXT NULL,
    ADD COLUMN payload_json LONGTEXT NULL,
    ADD COLUMN result_id VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_session_event_fact (run_id,run_sequence),
    ADD UNIQUE KEY uk_session_event_id (event_id);
UPDATE platform_agent_run_event e
JOIN platform_agent_session_event p ON p.run_id=e.run_id AND p.run_sequence=e.sequence_no
SET e.session_cursor=p.session_cursor;
UPDATE platform_agent_run_event SET event_id=CONCAT(run_id,':',sequence_no);
UPDATE platform_agent_session_event SET event_id=CONCAT(run_id,':',run_sequence) WHERE run_id IS NOT NULL;
UPDATE platform_agent_session s
LEFT JOIN (SELECT session_id,MAX(session_cursor) AS high_cursor FROM platform_agent_session_event GROUP BY session_id) p
ON p.session_id=s.session_id SET s.next_event_cursor=COALESCE(p.high_cursor,0)+1;
CREATE TEMPORARY TABLE missing_run_projection AS
SELECT e.run_id,e.sequence_no,r.session_id,
       s.next_event_cursor-1+ROW_NUMBER() OVER(PARTITION BY r.session_id ORDER BY e.created_at,e.run_id,e.sequence_no) AS cursor_value
FROM platform_agent_run_event e JOIN platform_agent_run r ON r.run_id=e.run_id
JOIN platform_agent_session s ON s.session_id=r.session_id WHERE e.session_cursor IS NULL;
UPDATE platform_agent_run_event e JOIN missing_run_projection m ON m.run_id=e.run_id AND m.sequence_no=e.sequence_no
SET e.session_cursor=m.cursor_value,e.payload_json='{"historicalProjection":true}';
INSERT INTO platform_agent_session_event(session_id,session_cursor,run_id,run_sequence,event_type,visibility,content,created_at,
    schema_version,event_id,origin_kind,payload_json)
SELECT m.session_id,m.cursor_value,e.run_id,e.sequence_no,e.event_type,e.visibility,e.content,e.created_at,
       e.schema_version,e.event_id,e.origin_kind,e.payload_json
FROM missing_run_projection m JOIN platform_agent_run_event e ON e.run_id=m.run_id AND e.sequence_no=m.sequence_no;
DROP TEMPORARY TABLE missing_run_projection;
UPDATE platform_agent_session s
LEFT JOIN (SELECT session_id,MAX(session_cursor) AS high_cursor FROM platform_agent_session_event GROUP BY session_id) p
ON p.session_id=s.session_id SET s.next_event_cursor=COALESCE(p.high_cursor,0)+1;
