-- P2：会话级持久事件投影。
-- run 内的 sequence_no 只保证单 Run 有序；会话流与历史分页需要跨 Run 单调的 session_cursor，
-- 这里为既有持久事件建立投影并按确定性顺序补入，写入侧随后与 run 事件在同一事务内同步投影。
CREATE TABLE platform_agent_session_event (
    session_id VARCHAR(36) NOT NULL,
    session_cursor BIGINT NOT NULL,
    run_id VARCHAR(36) NULL,
    run_sequence INT NULL,
    event_type VARCHAR(64) NOT NULL,
    visibility VARCHAR(32) NOT NULL DEFAULT 'USER',
    content VARCHAR(4000) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (session_id, session_cursor),
    KEY idx_platform_agent_session_event_run (run_id, run_sequence),
    CONSTRAINT fk_platform_agent_session_event_session FOREIGN KEY (session_id) REFERENCES platform_agent_session (session_id),
    CONSTRAINT fk_platform_agent_session_event_run FOREIGN KEY (run_id) REFERENCES platform_agent_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 历史事件按 (created_at, run_id, sequence_no) 编号补入投影，保证同一 Session 内游标严格递增。
INSERT INTO platform_agent_session_event (session_id, session_cursor, run_id, run_sequence, event_type, visibility, content, created_at)
SELECT run.session_id,
       ROW_NUMBER() OVER (PARTITION BY run.session_id ORDER BY event.created_at, event.run_id, event.sequence_no),
       event.run_id,
       event.sequence_no,
       event.event_type,
       'USER',
       event.content,
       event.created_at
FROM platform_agent_run_event event
JOIN platform_agent_run run ON run.run_id = event.run_id;
