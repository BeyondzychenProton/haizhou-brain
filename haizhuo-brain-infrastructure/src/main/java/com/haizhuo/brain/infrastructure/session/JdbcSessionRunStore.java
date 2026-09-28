package com.haizhuo.brain.infrastructure.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.HarnessRunSpec;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.SessionEvent;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.platform.run.SessionTimelineItem;
import com.haizhuo.brain.platform.run.RunGuidance;
import com.haizhuo.brain.platform.session.AgentSession;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 阻塞式 JDBC 适配器。调用方必须保证它不在 WebFlux 事件循环上执行。 */
@Repository
public class JdbcSessionRunStore implements SessionRunStore, RunControlInbox {
    private final JdbcTemplate jdbc;
    private final JdbcSessionEventProjector sessionEvents;

    public JdbcSessionRunStore(JdbcTemplate jdbc, JdbcSessionEventProjector sessionEvents) {
        this.jdbc = jdbc;
        this.sessionEvents = sessionEvents;
    }

    @Override
    public AgentSession createSession(AgentSession session) {
        jdbc.update("INSERT INTO platform_agent_session(session_id,user_id,employee_id,status,created_at,last_active_at,row_version) VALUES(?,?,?,?,?,?,?)",
                session.id().value(), session.userId().value(), session.employeeId(), session.status().name(),
                Timestamp.from(session.createdAt()), Timestamp.from(session.lastActiveAt()), session.rowVersion());
        return session;
    }

    @Override
    public Optional<AgentSession> findSession(SessionId sessionId, UserId owner) {
        return jdbc.query("SELECT session_id,user_id,employee_id,status,created_at,last_active_at,row_version FROM platform_agent_session WHERE session_id=? AND user_id=?",
                (rs, row) -> new AgentSession(new SessionId(rs.getString("session_id")), new UserId(rs.getLong("user_id")), rs.getLong("employee_id"),
                        AgentSession.Status.valueOf(rs.getString("status")), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_active_at")), rs.getLong("row_version")),
                sessionId.value(), owner.value()).stream().findFirst();
    }

    @Override public List<AgentSession> findSessions(UserId owner, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("SELECT session_id,user_id,employee_id,status,created_at,last_active_at,row_version FROM platform_agent_session WHERE user_id=? ORDER BY last_active_at DESC LIMIT ?",
                (rs, row) -> new AgentSession(new SessionId(rs.getString("session_id")), new UserId(rs.getLong("user_id")), rs.getLong("employee_id"), AgentSession.Status.valueOf(rs.getString("status")), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_active_at")), rs.getLong("row_version")), owner.value(), safeLimit);
    }

    @Override @Transactional
    public AgentRun createRun(AgentRun run, String input, HarnessRunSpec runSpec) {
        Optional<AgentRun> replay = findByRequest(run.userId(), run.clientRequestId());
        if (replay.isPresent()) {
            AgentRun existing = replay.get();
            if (!existing.sessionId().equals(run.sessionId()) || !existing.inputDigest().equals(run.inputDigest())) throw new IllegalStateException("Client request id conflicts with an existing run");
            return existing;
        }
        var sessions = jdbc.query("SELECT employee_id FROM platform_agent_session WHERE session_id=? AND user_id=? AND status='ACTIVE' FOR UPDATE", (rs, row) -> rs.getLong(1), run.sessionId().value(), run.userId().value());
        if (sessions.isEmpty()) throw new IllegalArgumentException("Active session was not found");
        if (sessions.get(0) != run.employeeId()) throw new IllegalStateException("Run employee does not match session employee");
        try {
            jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,created_at) VALUES(?,?,?,?,?,?,?,?,?)", run.id().value(), run.sessionId().value(), run.userId().value(), run.employeeId(), run.definitionVersionId(), run.clientRequestId(), run.inputDigest(), run.state().name(), Timestamp.from(run.createdAt()));
        } catch (DuplicateKeyException error) { throw new IllegalStateException("Client request id conflicts with an existing run", error); }
        jdbc.update("INSERT INTO platform_agent_run_spec(run_id,definition_version_id,definition_bundle_id,definition_bundle_hash,model_visible_tool_names_json,tool_view_hash,effective_capability_hash,channel_type,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                runSpec.runId().value(), runSpec.definitionVersionId(), runSpec.definitionBundleId(), runSpec.definitionBundleHash(),
                runSpec.modelVisibleToolNamesJson(), runSpec.toolViewHash(), runSpec.effectiveCapabilityHash(), runSpec.channelType(), Timestamp.from(runSpec.createdAt()));
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) VALUES(?,?,?,?,?)", run.id().value(), 1, "USER_INPUT", input, Timestamp.from(run.createdAt()));
        sessionEvents.project(run.sessionId(), run.id(), 1, "USER_INPUT", input, run.createdAt());
        jdbc.update("UPDATE platform_agent_session SET last_active_at=?,row_version=row_version+1 WHERE session_id=? AND user_id=?", Timestamp.from(run.createdAt()), run.sessionId().value(), run.userId().value());
        return run;
    }

    @Override
    public Optional<AgentRun> findRun(RunId runId, UserId owner) {
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,created_at,started_at,finished_at FROM platform_agent_run WHERE run_id=? AND user_id=?",
                (rs, row) -> new AgentRun(new RunId(rs.getString("run_id")), new SessionId(rs.getString("session_id")), new UserId(rs.getLong("user_id")),
                        rs.getLong("employee_id"), rs.getLong("definition_version_id"), rs.getString("client_request_id"), rs.getString("input_digest"),
                        RunState.valueOf(rs.getString("state")), instant(rs.getTimestamp("created_at")), nullableInstant(rs.getTimestamp("started_at")),
                        nullableInstant(rs.getTimestamp("finished_at"))), runId.value(), owner.value()).stream().findFirst();
    }

    @Override public java.util.List<RunEvent> findEvents(RunId runId, UserId owner, int afterSequence, int limit) {
        return jdbc.query("SELECT event.sequence_no,event.event_type,event.content,event.created_at FROM platform_agent_run_event event JOIN platform_agent_run run ON run.run_id=event.run_id WHERE event.run_id=? AND run.user_id=? AND event.sequence_no>? ORDER BY event.sequence_no LIMIT ?", (rs, row) -> new RunEvent(runId, rs.getInt("sequence_no"), rs.getString("event_type"), rs.getString("content"), instant(rs.getTimestamp("created_at"))), runId.value(), owner.value(), afterSequence, limit);
    }

    @Override public java.util.List<SessionEvent> findSessionEvents(SessionId sessionId, UserId owner, long afterCursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query("SELECT event.session_id,event.session_cursor,event.run_id,event.run_sequence,event.event_type,"
                        + "event.visibility,event.content,event.created_at FROM platform_agent_session_event event "
                        + "JOIN platform_agent_session session ON session.session_id=event.session_id "
                        + "WHERE event.session_id=? AND session.user_id=? AND event.session_cursor>? "
                        + "ORDER BY event.session_cursor LIMIT ?",
                (rs, row) -> new SessionEvent(new SessionId(rs.getString("session_id")), rs.getLong("session_cursor"),
                        new RunId(rs.getString("run_id")), nullableInt(rs, "run_sequence"), rs.getString("event_type"),
                        EventVisibility.valueOf(rs.getString("visibility")), rs.getString("content"),
                        instant(rs.getTimestamp("created_at"))), sessionId.value(), owner.value(), afterCursor, safeLimit);
    }

    @Override public long oldestSessionCursor(SessionId sessionId, UserId owner) {
        Long floor = jdbc.query("SELECT MIN(event.session_cursor) FROM platform_agent_session_event event "
                        + "JOIN platform_agent_session session ON session.session_id=event.session_id "
                        + "WHERE event.session_id=? AND session.user_id=?",
                (rs, row) -> rs.getObject(1) == null ? 0L : rs.getLong(1),
                sessionId.value(), owner.value()).stream().findFirst().orElse(0L);
        return floor == null ? 0L : floor;
    }

    @Override public int trimSessionEvents(SessionId sessionId, UserId owner, int keepLatest) {
        if (keepLatest <= 0) {
            return 0;
        }
        // 先确认属主，避免越权裁剪他人会话的历史。
        Long max = jdbc.query("SELECT COALESCE(MAX(event.session_cursor),0) FROM platform_agent_session_event event "
                        + "JOIN platform_agent_session session ON session.session_id=event.session_id "
                        + "WHERE event.session_id=? AND session.user_id=?",
                (rs, row) -> rs.getLong(1), sessionId.value(), owner.value()).stream().findFirst().orElse(0L);
        // 分两步取边界再删除：不依赖 MySQL 与 H2 一致支持的自引用删除子查询。
        // 并发写入只会抬高边界下界，已算好的 boundary 仍落在更旧的一侧，因此只可能少删、不会误删。
        long boundary = (max == null ? 0L : max) - keepLatest;
        if (boundary <= 0) {
            return 0;
        }
        return jdbc.update("DELETE FROM platform_agent_session_event WHERE session_id=? AND session_cursor<=?",
                sessionId.value(), boundary);
    }

    @Override public List<AgentRun> findRuns(SessionId sessionId, UserId owner, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,created_at,started_at,finished_at FROM platform_agent_run WHERE session_id=? AND user_id=? ORDER BY created_at DESC,run_id DESC LIMIT ?",
                (rs, row) -> new AgentRun(new RunId(rs.getString("run_id")), new SessionId(rs.getString("session_id")), new UserId(rs.getLong("user_id")),
                        rs.getLong("employee_id"), rs.getLong("definition_version_id"), rs.getString("client_request_id"), rs.getString("input_digest"),
                        RunState.valueOf(rs.getString("state")), instant(rs.getTimestamp("created_at")), nullableInstant(rs.getTimestamp("started_at")),
                        nullableInstant(rs.getTimestamp("finished_at"))), sessionId.value(), owner.value(), safeLimit);
    }

    @Override public List<SessionTimelineItem> findTimeline(SessionId sessionId, UserId owner, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query("SELECT event.run_id,event.sequence_no,event.event_type,event.content,event.created_at FROM platform_agent_run_event event JOIN platform_agent_run run ON run.run_id=event.run_id WHERE run.session_id=? AND run.user_id=? ORDER BY event.created_at DESC,event.run_id DESC,event.sequence_no DESC LIMIT ?",
                (rs, row) -> new SessionTimelineItem(new RunId(rs.getString("run_id")), rs.getInt("sequence_no"), rs.getString("event_type"), rs.getString("content"), instant(rs.getTimestamp("created_at"))), sessionId.value(), owner.value(), safeLimit).stream().sorted(java.util.Comparator.comparing(SessionTimelineItem::createdAt).thenComparing(item -> item.runId().value()).thenComparingInt(SessionTimelineItem::sequenceNo)).toList();
    }

    @Override public int queuePosition(RunId runId, UserId owner) {
        return jdbc.query("SELECT 1 + (SELECT COUNT(*) FROM platform_agent_run earlier WHERE earlier.session_id=run.session_id AND earlier.state='QUEUED' AND (earlier.created_at<run.created_at OR (earlier.created_at=run.created_at AND earlier.run_id<run.run_id))) FROM platform_agent_run run WHERE run.run_id=? AND run.user_id=? AND run.state='QUEUED'",
                (rs, row) -> rs.getInt(1), runId.value(), owner.value()).stream().findFirst().orElse(0);
    }

    @Override @Transactional public AgentRun cancel(RunId runId, UserId owner) {
        AgentRun run = findRun(runId, owner).orElseThrow(() -> new IllegalArgumentException("Run was not found"));
        Instant now = Instant.now();
        if (run.state() == RunState.QUEUED) {
            // §47.1：排队的 run 尚未开始，直接取消。
            if (jdbc.update("UPDATE platform_agent_run SET state='CANCELLED',finished_at=?,cancel_requested_at=? WHERE run_id=? AND state='QUEUED'", Timestamp.from(now), Timestamp.from(now), runId.value()) == 1)
                appendEvent(runId, "RUN_CANCELLED", "排队消息已取消", now);
        } else if (run.state() == RunState.RUNNING) {
            // §47.2：持久化的取消标记；运行时会在下一个安全检查点确认。
            if (jdbc.update("UPDATE platform_agent_run SET state='CANCELLING',cancel_requested_at=? WHERE run_id=? AND state='RUNNING'", Timestamp.from(now), runId.value()) == 1)
                appendEvent(runId, "RUN_CANCEL_REQUESTED", "已请求在下一个安全检查点取消", now);
        } else if (run.state() == RunState.WAITING_TOOL) {
            // §47.3：尚未开始的工具工作被取消；执行中的工具是一次无法假称回滚的副作用，
            // 因此如实地拒绝该取消请求。
            Integer executing = jdbc.queryForObject("SELECT COUNT(*) FROM platform_tool_execution WHERE run_id=? AND state='EXECUTING'", Integer.class, runId.value());
            if (executing != null && executing > 0)
                throw new IllegalStateException("工具正在执行中，暂时无法取消，请稍后重试");
            jdbc.update("UPDATE platform_tool_execution SET state='CANCELLED',updated_at=? WHERE run_id=? AND state IN ('REQUESTED','APPROVED')", Timestamp.from(now), runId.value());
            if (jdbc.update("UPDATE platform_agent_run SET state='CANCELLED',finished_at=?,cancel_requested_at=? WHERE run_id=? AND state='WAITING_TOOL'", Timestamp.from(now), Timestamp.from(now), runId.value()) == 1)
                appendEvent(runId, "RUN_CANCELLED", "等待工具结果的运行已取消", now);
        } else if (run.state() == RunState.WAITING_CONFIRMATION) {
            // §47.4：关闭未决审批、取消执行、取消该 run。
            // 可移植的子查询写法（MySQL 的 UPDATE...JOIN 在 H2 测试中不受支持）。
            jdbc.update("UPDATE platform_tool_approval SET decision='REJECTED',reason='运行已取消',decided_at=? "
                    + "WHERE decision='PENDING' AND tool_execution_id IN "
                    + "(SELECT tool_execution_id FROM platform_tool_execution WHERE run_id=?)", Timestamp.from(now), runId.value());
            jdbc.update("UPDATE platform_tool_execution SET state='CANCELLED',updated_at=? WHERE run_id=? AND state IN ('APPROVAL_REQUIRED','REQUESTED','APPROVED')", Timestamp.from(now), runId.value());
            if (jdbc.update("UPDATE platform_agent_run SET state='CANCELLED',finished_at=?,cancel_requested_at=? WHERE run_id=? AND state='WAITING_CONFIRMATION'", Timestamp.from(now), Timestamp.from(now), runId.value()) == 1)
                appendEvent(runId, "RUN_CANCELLED", "等待人工确认的运行已取消", now);
        } else if (run.state() != RunState.CANCELLING) {
            throw new IllegalStateException("Terminal Run cannot be cancelled");
        }
        return findRun(runId, owner).orElseThrow();
    }

    @Override @Transactional public RunGuidance addGuidance(RunId runId, UserId owner, String source, String content) {
        AgentRun run = findRun(runId, owner).orElseThrow(() -> new IllegalArgumentException("Run was not found"));
        if (run.state() != RunState.RUNNING) throw new IllegalStateException("Guidance is accepted only while a Run is running");
        Instant now = Instant.now(); String id = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_agent_run_guidance(guidance_id,run_id,author_user_id,source,content,status,created_at) VALUES(?,?,?,?,?,'PENDING',?)", id, runId.value(), owner.value(), source, content, Timestamp.from(now));
        appendEvent(runId, "RUN_GUIDANCE_RECEIVED", "已收到运行中引导", now);
        return new RunGuidance(id, runId, owner, source, content, RunGuidance.Status.PENDING, now, null);
    }

    @Override @Transactional public List<RunGuidanceMessage> consumeGuidance(RunId runId) {
        Instant now = Instant.now();
        List<RunGuidanceMessage> messages = jdbc.query("SELECT guidance_id,source,content,created_at FROM platform_agent_run_guidance WHERE run_id=? AND status='PENDING' ORDER BY created_at,guidance_id FOR UPDATE", (rs,row) -> new RunGuidanceMessage(rs.getString("guidance_id"), runId, rs.getString("source"), rs.getString("content"), instant(rs.getTimestamp("created_at"))), runId.value());
        for (RunGuidanceMessage message : messages) {
            jdbc.update("UPDATE platform_agent_run_guidance SET status='CONSUMED',consumed_at=? WHERE guidance_id=? AND status='PENDING'", Timestamp.from(now), message.guidanceId());
            appendEvent(runId, "RUN_GUIDANCE_CONSUMED", "运行中引导已在下一轮推理前生效", now);
        }
        return List.copyOf(messages);
    }

    @Override public boolean isCancellationRequested(RunId runId) {
        return jdbc.query("SELECT 1 FROM platform_agent_run WHERE run_id=? AND state='CANCELLING'", (rs,row) -> 1, runId.value()).stream().findFirst().isPresent();
    }

    /** run 事件与会话投影必须在同一事务内写入，否则会话游标会出现空洞。 */
    private void appendEvent(RunId id, String type, String text, Instant now) {
        jdbc.queryForObject("SELECT run_id FROM platform_agent_run WHERE run_id=? FOR UPDATE", String.class, id.value());
        int sequence = nextSequence(id);
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) VALUES(?,?,?,?,?)",
                id.value(), sequence, type, text, Timestamp.from(now));
        sessionEvents.project(sessionIdOf(id), id, sequence, type, text, now);
    }

    private int nextSequence(RunId runId) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0)+1 FROM platform_agent_run_event WHERE run_id=?",
                Integer.class, runId.value());
        return next == null ? 1 : next;
    }

    private SessionId sessionIdOf(RunId runId) {
        return new SessionId(jdbc.queryForObject("SELECT session_id FROM platform_agent_run WHERE run_id=?",
                String.class, runId.value()));
    }

    private Optional<AgentRun> findByRequest(UserId owner, String clientRequestId) {
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,created_at,started_at,finished_at FROM platform_agent_run WHERE user_id=? AND client_request_id=?", (rs, row) -> new AgentRun(new RunId(rs.getString("run_id")), new SessionId(rs.getString("session_id")), new UserId(rs.getLong("user_id")), rs.getLong("employee_id"), rs.getLong("definition_version_id"), rs.getString("client_request_id"), rs.getString("input_digest"), RunState.valueOf(rs.getString("state")), instant(rs.getTimestamp("created_at")), nullableInstant(rs.getTimestamp("started_at")), nullableInstant(rs.getTimestamp("finished_at"))), owner.value(), clientRequestId).stream().findFirst();
    }

    private static Instant instant(Timestamp timestamp) { return timestamp.toInstant(); }
    private static Instant nullableInstant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
