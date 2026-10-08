package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.infrastructure.session.JdbcSessionEventProjector;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.run.DurableEventMetadata;
import com.haizhuo.brain.platform.run.EventVisibility;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 唯一平台事实写入入口；加入业务事务，不自行提交，不创建独立事务。 */
@Repository
public class JdbcRunEventAppender {
    private final JdbcTemplate jdbc;
    private final JdbcSessionEventProjector projector;
    public JdbcRunEventAppender(JdbcTemplate jdbc, JdbcSessionEventProjector projector) {
        this.jdbc = jdbc;
        this.projector = projector;
    }
    public static void requireTransaction(JdbcTemplate jdbc) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Run facts require the current JDBC business transaction");
    }
    /** 所有业务写入必须先调用；候选扫描不得先锁 Run/attempt/tool。 */
    public SessionId lockRun(RunId runId) { return lockRun(runId, false); }
    public SessionId tryLockRun(RunId runId) { return lockRun(runId, true); }
    private SessionId lockRun(RunId runId, boolean skipLocked) {
        requireTransaction(jdbc);
        List<String> ids = jdbc.query("SELECT session_id FROM platform_agent_run WHERE run_id=?",
                (rs, n) -> rs.getString(1), runId.value());
        if (ids.isEmpty()) return null;
        String clause = skipLocked ? " FOR UPDATE SKIP LOCKED" : " FOR UPDATE";
        if (jdbc.query("SELECT session_id FROM platform_agent_session WHERE session_id=?" + clause,
                (rs, n) -> rs.getString(1), ids.get(0)).isEmpty()) return null;
        if (jdbc.query("SELECT run_id FROM platform_agent_run WHERE run_id=? AND session_id=?" + clause,
                (rs, n) -> rs.getString(1), runId.value(), ids.get(0)).isEmpty()) return null;
        return new SessionId(ids.get(0));
    }
    public long append(RunId runId, String type, String content, Instant now) {
        return append(runId, type, content, EventVisibility.USER, DurableEventMetadata.platform(), null, now);
    }
    public long append(RunId runId, String type, String content, EventVisibility visibility,
                       DurableEventMetadata metadata, String businessKey, Instant now) {
        SessionId sessionId = lockRun(runId);
        if (sessionId == null) throw new IllegalStateException("Run or session missing");
        if (visibility == EventVisibility.USER && ("CHILD".equals(metadata.originKind()) || "UNKNOWN".equals(metadata.originKind())))
            throw new IllegalArgumentException("Internal execution cannot publish a root user event");
        String dedup = businessKey;
        if (dedup == null && metadata.nativeRefs() != null && metadata.nativeRefs().nativeEventId() != null)
            dedup = "native:" + metadata.attemptId() + ":" + metadata.nativeRefs().nativeEventId() + ":" + type;
        String key = dedup == null ? null : CanonicalJson.sha256Hex(dedup.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            List<Long> replay = jdbc.query("SELECT session_cursor FROM platform_agent_run_event WHERE run_id=? AND business_key=? FOR UPDATE",
                    (rs, n) -> rs.getLong(1), runId.value(), key);
            if (!replay.isEmpty()) return replay.get(0);
        }
        var latest = jdbc.query("SELECT attempt_id,fence_token FROM platform_run_execution_attempt WHERE run_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE",
                (rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)}, runId.value());
        String attempt = metadata.attemptId();
        Long fence = metadata.fenceToken();
        if (attempt != null || fence != null) {
            if (latest.isEmpty() || !java.util.Objects.equals(attempt, latest.get(0)[0]) || !java.util.Objects.equals(fence, latest.get(0)[1]))
                throw new IllegalStateException("Stale event source");
        } else if (!latest.isEmpty()) {
            attempt = (String) latest.get(0)[0]; fence = (Long) latest.get(0)[1];
        }
        // MySQL REPEATABLE READ may retain a snapshot created before waiting for the Session lock.
        // Read the last row with a locking current read; a snapshot MAX can reuse a committed sequence.
        List<Integer> previous = jdbc.query("SELECT sequence_no FROM platform_agent_run_event WHERE run_id=? "
                        + "ORDER BY sequence_no DESC LIMIT 1 FOR UPDATE", (rs, n) -> rs.getInt(1), runId.value());
        int seq = previous.isEmpty() ? 1 : previous.get(0) + 1;
        long cursor = projector.allocateCursor(sessionId);
        String eventId = runId.value() + ":" + seq;
        DurableEventMetadata stored = new DurableEventMetadata(metadata.schemaVersion(), eventId, attempt, fence,
                metadata.originKind(), metadata.nativeRefs(), metadata.payload(), metadata.resultId(), cursor);
        String safe = summary(content);
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at,session_cursor,"
                        + "schema_version,event_id,business_key,visibility,attempt_id,fence_token,origin_kind,native_refs_json,payload_json,result_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runId.value(), seq, type, safe, Timestamp.from(now), cursor, stored.schemaVersion(), eventId, key,
                visibility.name(), attempt, fence, stored.originKind(), JdbcEventMetadata.json(stored.nativeRefs()), JdbcEventMetadata.json(stored.payload()), stored.resultId());
        projector.projectAt(sessionId, cursor, runId, seq, type, safe, visibility, stored, now);
        return cursor;
    }
    /** 摘要按 Unicode 边界裁剪；完整结果独立保存。 */
    public static String summary(String text) {
        if (text == null) return "";
        if (text.length() <= 4000) return text;
        int end = Character.isHighSurrogate(text.charAt(3999)) ? 3999 : 4000;
        return text.substring(0, end);
    }
}
