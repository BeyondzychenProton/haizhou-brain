package com.haizhuo.brain.infrastructure.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.ClaimedRun;
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

/** Blocking JDBC adapter. Callers must keep it off the WebFlux event loop. */
@Repository
public class JdbcSessionRunStore implements SessionRunStore, RunControlInbox {
    private final JdbcTemplate jdbc;

    public JdbcSessionRunStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
    public AgentRun createRun(AgentRun run, String input) {
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
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) VALUES(?,?,?,?,?)", run.id().value(), 1, "USER_INPUT", input, Timestamp.from(run.createdAt()));
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
            if (jdbc.update("UPDATE platform_agent_run SET state='CANCELLED',finished_at=?,cancel_requested_at=? WHERE run_id=? AND state='QUEUED'", Timestamp.from(now), Timestamp.from(now), runId.value()) == 1)
                appendEvent(runId, "RUN_CANCELLED", "排队消息已取消", now);
        } else if (run.state() == RunState.RUNNING) {
            if (jdbc.update("UPDATE platform_agent_run SET state='CANCELLING',cancel_requested_at=? WHERE run_id=? AND state='RUNNING'", Timestamp.from(now), runId.value()) == 1)
                appendEvent(runId, "RUN_CANCEL_REQUESTED", "已请求在下一个安全检查点取消", now);
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

    @Override @Transactional
    public Optional<ClaimedRun> claimNextQueuedRun() {
        Instant now = Instant.now();
        var claimed = jdbc.query("SELECT r.run_id,r.session_id,r.user_id,r.employee_id,r.definition_version_id,r.client_request_id,r.input_digest,r.created_at,e.display_name,v.instructions,v.model_provider,v.model_name,i.content FROM platform_agent_run r JOIN digital_employee e ON e.id=r.employee_id JOIN agent_definition_version v ON v.id=r.definition_version_id JOIN platform_agent_run_event i ON i.run_id=r.run_id AND i.sequence_no=1 WHERE r.state='QUEUED' AND NOT EXISTS (SELECT 1 FROM platform_agent_run active WHERE active.session_id=r.session_id AND active.state IN ('RUNNING','CANCELLING')) ORDER BY r.created_at,r.run_id LIMIT 1 FOR UPDATE SKIP LOCKED", (rs,n) -> new ClaimedRun(new AgentRun(new RunId(rs.getString("run_id")),new SessionId(rs.getString("session_id")),new UserId(rs.getLong("user_id")),rs.getLong("employee_id"),rs.getLong("definition_version_id"),rs.getString("client_request_id"),rs.getString("input_digest"),RunState.RUNNING,instant(rs.getTimestamp("created_at")),now,null),rs.getString("display_name"),rs.getString("instructions"),rs.getString("model_provider"),rs.getString("model_name"),rs.getString("content"))).stream().findFirst();
        claimed.ifPresent(run -> { jdbc.update("UPDATE platform_agent_run SET state='RUNNING',started_at=? WHERE run_id=? AND state='QUEUED'",Timestamp.from(now),run.run().id().value()); jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) VALUES(?,2,'RUN_STARTED','执行已开始',?)",run.run().id().value(),Timestamp.from(now)); });
        return claimed;
    }
    @Override @Transactional public void complete(RunId id,String result) { finish(id,"SUCCEEDED","RUN_COMPLETED",result,"RUNNING"); }
    @Override @Transactional public void fail(RunId id,String reason) { finish(id,"FAILED","RUN_FAILED",reason,"RUNNING"); }
    @Override @Transactional public void cancelled(RunId id,String reason) { finish(id,"CANCELLED","RUN_CANCELLED",reason,"CANCELLING"); }
    private void finish(RunId id,String state,String type,String text,String expectedState) { Instant now=Instant.now(); if(jdbc.update("UPDATE platform_agent_run SET state=?,finished_at=? WHERE run_id=? AND state=?",state,Timestamp.from(now),id.value(),expectedState)==1) appendEvent(id,type,text,now); }

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

    private void appendEvent(RunId id, String type, String text, Instant now) {
        // Serialize sequence allocation with state/control writes for this Run.
        jdbc.queryForObject("SELECT run_id FROM platform_agent_run WHERE run_id=? FOR UPDATE", String.class, id.value());
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) SELECT ?,COALESCE(MAX(sequence_no),0)+1,?,?,? FROM platform_agent_run_event WHERE run_id=?",id.value(),type,text,Timestamp.from(now),id.value());
    }

    private Optional<AgentRun> findByRequest(UserId owner, String clientRequestId) {
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,created_at,started_at,finished_at FROM platform_agent_run WHERE user_id=? AND client_request_id=?", (rs, row) -> new AgentRun(new RunId(rs.getString("run_id")), new SessionId(rs.getString("session_id")), new UserId(rs.getLong("user_id")), rs.getLong("employee_id"), rs.getLong("definition_version_id"), rs.getString("client_request_id"), rs.getString("input_digest"), RunState.valueOf(rs.getString("state")), instant(rs.getTimestamp("created_at")), nullableInstant(rs.getTimestamp("started_at")), nullableInstant(rs.getTimestamp("finished_at"))), owner.value(), clientRequestId).stream().findFirst();
    }

    private static Instant instant(Timestamp timestamp) { return timestamp.toInstant(); }
    private static Instant nullableInstant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
