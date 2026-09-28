package com.haizhuo.brain.infrastructure.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.run.EventVisibility;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 会话级事件投影的写入方（P2）。run 事件的每个写入点必须在同一事务内调用它，
 * 否则会话游标会出现空洞，会话流就无法在断线后按游标补读。
 * 游标分配先锁住 session 行，与既有 run 事件序号分配保持同样的串行化方式。
 */
@Repository
public class JdbcSessionEventProjector {
    private static final int MAX_CONTENT = 4000;

    private final JdbcTemplate jdbc;

    public JdbcSessionEventProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void project(SessionId sessionId, RunId runId, Integer runSequence, String type,
                        String content, Instant occurredAt) {
        project(sessionId, runId, runSequence, type, content, EventVisibility.USER, occurredAt);
    }

    public void project(SessionId sessionId, RunId runId, Integer runSequence, String type,
                        String content, EventVisibility visibility, Instant occurredAt) {
        jdbc.queryForObject("SELECT session_id FROM platform_agent_session WHERE session_id=? FOR UPDATE",
                String.class, sessionId.value());
        jdbc.update("INSERT INTO platform_agent_session_event(session_id,session_cursor,run_id,run_sequence,"
                        + "event_type,visibility,content,created_at) "
                        + "SELECT ?,COALESCE(MAX(session_cursor),0)+1,?,?,?,?,?,? "
                        + "FROM platform_agent_session_event WHERE session_id=?",
                sessionId.value(), runId == null ? null : runId.value(), runSequence, type,
                visibility.name(), truncate(content), Timestamp.from(occurredAt), sessionId.value());
    }

    private static String truncate(String content) {
        return content != null && content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
    }
}
