package com.haizhuo.brain.infrastructure.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.platform.session.AgentSession;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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

    @Override
    public Optional<AgentRun> findRun(RunId runId, UserId owner) {
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,created_at,started_at,finished_at FROM platform_agent_run WHERE run_id=? AND user_id=?",
                (rs, row) -> new AgentRun(new RunId(rs.getString("run_id")), new SessionId(rs.getString("session_id")), new UserId(rs.getLong("user_id")),
                        rs.getLong("employee_id"), rs.getLong("definition_version_id"), rs.getString("client_request_id"), rs.getString("input_digest"),
                        RunState.valueOf(rs.getString("state")), instant(rs.getTimestamp("created_at")), nullableInstant(rs.getTimestamp("started_at")),
                        nullableInstant(rs.getTimestamp("finished_at"))), runId.value(), owner.value()).stream().findFirst();
    }

    private static Instant instant(Timestamp timestamp) { return timestamp.toInstant(); }
    private static Instant nullableInstant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
