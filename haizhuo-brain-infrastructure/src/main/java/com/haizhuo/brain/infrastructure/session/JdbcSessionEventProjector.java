package com.haizhuo.brain.infrastructure.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.infrastructure.run.JdbcEventMetadata;
import com.haizhuo.brain.infrastructure.run.JdbcRunEventAppender;
import com.haizhuo.brain.platform.run.DurableEventMetadata;
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
        long cursor = allocateCursor(sessionId);
        projectAt(sessionId, cursor, runId, runSequence, type, JdbcRunEventAppender.summary(content), visibility,
                DurableEventMetadata.legacy(), occurredAt);
    }

    public long allocateCursor(SessionId sessionId) {
        JdbcRunEventAppender.requireTransaction(jdbc);
        Long cursor = jdbc.queryForObject("SELECT next_event_cursor FROM platform_agent_session WHERE session_id=? FOR UPDATE",
                Long.class, sessionId.value());
        if (cursor == null || cursor < 1) throw new IllegalStateException("Session cursor is invalid");
        jdbc.update("UPDATE platform_agent_session SET next_event_cursor=? WHERE session_id=?", Math.addExact(cursor, 1L), sessionId.value());
        return cursor;
    }

    public void projectAt(SessionId sessionId, long cursor, RunId runId, Integer runSequence, String type,
                          String content, EventVisibility visibility, DurableEventMetadata metadata, Instant occurredAt) {
        JdbcRunEventAppender.requireTransaction(jdbc);
        jdbc.update("INSERT INTO platform_agent_session_event(session_id,session_cursor,run_id,run_sequence,event_type,visibility,"
                        + "content,created_at,schema_version,event_id,attempt_id,fence_token,origin_kind,native_refs_json,payload_json,result_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                sessionId.value(), cursor, runId == null ? null : runId.value(), runSequence, type, visibility.name(),
                content, Timestamp.from(occurredAt), metadata.schemaVersion(), metadata.eventId(), metadata.attemptId(),
                metadata.fenceToken(), metadata.originKind(), JdbcEventMetadata.json(metadata.nativeRefs()), JdbcEventMetadata.json(metadata.payload()), metadata.resultId());
    }
}
