package com.haizhuo.brain.infrastructure.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.platform.session.AgentSession;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Blocking JDBC adapter. Callers must keep it off the WebFlux event loop. */
@Repository
public class JdbcSessionRunStore implements SessionRunStore {
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
        } catch (DuplicateKeyException error) { throw new IllegalStateException("Session already has an active run", error); }
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

    private Optional<AgentRun> findByRequest(UserId owner, String clientRequestId) {
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,created_at,started_at,finished_at FROM platform_agent_run WHERE user_id=? AND client_request_id=?", (rs, row) -> new AgentRun(new RunId(rs.getString("run_id")), new SessionId(rs.getString("session_id")), new UserId(rs.getLong("user_id")), rs.getLong("employee_id"), rs.getLong("definition_version_id"), rs.getString("client_request_id"), rs.getString("input_digest"), RunState.valueOf(rs.getString("state")), instant(rs.getTimestamp("created_at")), nullableInstant(rs.getTimestamp("started_at")), nullableInstant(rs.getTimestamp("finished_at"))), owner.value(), clientRequestId).stream().findFirst();
    }

    private static Instant instant(Timestamp timestamp) { return timestamp.toInstant(); }
    private static Instant nullableInstant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
