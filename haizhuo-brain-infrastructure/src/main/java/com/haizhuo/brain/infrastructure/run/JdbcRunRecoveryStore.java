package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunRecoveryStore;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Atomically writes the administrative audit, terminal state, attempt state, and durable event. */
@Repository
public class JdbcRunRecoveryStore implements RunRecoveryStore {
    private final JdbcTemplate jdbc;
    private final JdbcRunEventAppender events;

    public JdbcRunRecoveryStore(JdbcTemplate jdbc, JdbcRunEventAppender events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional
    public AgentRun terminate(RunId runId, UserId actor, long expectedFenceToken, String requestId,
                             String stopEvidenceReference, String reason) {
        AgentRun replay = findReplay(requestId, runId, actor, expectedFenceToken, stopEvidenceReference, reason);
        if (replay != null) return replay;

        if (events.lockRun(runId) == null) throw new IllegalArgumentException("Run was not found");
        // A concurrent retry may have committed while this request waited for the Run/session lock.
        replay = findReplay(requestId, runId, actor, expectedFenceToken, stopEvidenceReference, reason);
        if (replay != null) return replay;

        List<AgentRun> rows = jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,"
                        + "client_request_id,input_digest,state,runtime_profile,created_at,started_at,finished_at "
                        + "FROM platform_agent_run WHERE run_id=? FOR UPDATE",
                (rs, n) -> mapRun(rs), runId.value());
        if (rows.isEmpty()) throw new IllegalArgumentException("Run was not found");
        AgentRun run = rows.get(0);
        if (run.state() != RunState.RECOVERY_REQUIRED)
            throw new IllegalStateException("Only a RECOVERY_REQUIRED Run can be terminated");
        if (run.runtimeProfile() == RuntimeProfile.LEGACY_STABLE)
            throw new IllegalStateException("Legacy Runs do not use administrative recovery termination");

        List<String> attempts = jdbc.query("SELECT attempt_id FROM platform_run_execution_attempt "
                        + "WHERE run_id=? AND fence_token=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE",
                (rs, n) -> rs.getString(1), runId.value(), expectedFenceToken);
        if (attempts.isEmpty()) throw new IllegalStateException("Expected execution fence does not match the Run");
        String attemptId = attempts.get(0);
        Instant now = Instant.now();
        try {
            jdbc.update("INSERT INTO platform_run_recovery_action(request_id,run_id,actor_user_id,expected_fence_token,"
                            + "stop_evidence_reference,reason,created_at) VALUES(?,?,?,?,?,?,?)",
                    requestId, runId.value(), actor.value(), expectedFenceToken, stopEvidenceReference, reason,
                    Timestamp.from(now));
        } catch (DuplicateKeyException duplicate) {
            replay = findReplay(requestId, runId, actor, expectedFenceToken, stopEvidenceReference, reason);
            if (replay != null) return replay;
            throw new IllegalStateException("Recovery request id conflicts with an existing action", duplicate);
        }
        int attemptUpdated = jdbc.update("UPDATE platform_run_execution_attempt SET state='CANCELLED',finished_at=? "
                        + "WHERE attempt_id=? AND run_id=? AND fence_token=? AND state='RECOVERY_REQUIRED'",
                Timestamp.from(now), attemptId, runId.value(), expectedFenceToken);
        if (attemptUpdated != 1) throw new IllegalStateException("Recovery attempt changed before termination");
        int runUpdated = jdbc.update("UPDATE platform_agent_run SET state='TERMINATED',finished_at=? "
                        + "WHERE run_id=? AND state='RECOVERY_REQUIRED'", Timestamp.from(now), runId.value());
        if (runUpdated != 1) throw new IllegalStateException("Run changed before recovery termination");
        events.append(runId, "RUN_RECOVERY_TERMINATED", "管理员已结束待核查运行。", now);
        return findRun(runId);
    }

    private AgentRun findReplay(String requestId, RunId runId, UserId actor, long fence,
                                String evidence, String reason) {
        List<Audit> rows = jdbc.query("SELECT run_id,actor_user_id,expected_fence_token,stop_evidence_reference,reason "
                        + "FROM platform_run_recovery_action WHERE request_id=? FOR UPDATE",
                (rs, n) -> new Audit(rs.getString("run_id"), rs.getLong("actor_user_id"),
                        rs.getLong("expected_fence_token"), rs.getString("stop_evidence_reference"), rs.getString("reason")),
                requestId);
        if (rows.isEmpty()) return null;
        Audit audit = rows.get(0);
        if (!audit.runId().equals(runId.value()) || audit.actorUserId() != actor.value()
                || audit.fenceToken() != fence || !audit.evidence().equals(evidence) || !audit.reason().equals(reason))
            throw new IllegalStateException("Recovery request id was already used with different details");
        return findRun(runId);
    }

    private AgentRun findRun(RunId runId) {
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,"
                        + "input_digest,state,runtime_profile,created_at,started_at,finished_at "
                        + "FROM platform_agent_run WHERE run_id=?",
                (rs, n) -> mapRun(rs), runId.value()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Run was not found"));
    }

    private static AgentRun mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new AgentRun(new RunId(rs.getString("run_id")), new SessionId(rs.getString("session_id")),
                new UserId(rs.getLong("user_id")), rs.getLong("employee_id"), rs.getLong("definition_version_id"),
                rs.getString("client_request_id"), rs.getString("input_digest"), RunState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(), started == null ? null : started.toInstant(),
                finished == null ? null : finished.toInstant(), RuntimeProfile.valueOf(rs.getString("runtime_profile")));
    }

    private record Audit(String runId, long actorUserId, long fenceToken, String evidence, String reason) { }
}
