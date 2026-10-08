package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.runtime.api.team.TeamExecutionMember;
import com.haizhuo.brain.runtime.api.team.TeamExecutionPersistence;
import com.haizhuo.brain.runtime.api.team.TeamExecutionSnapshot;
import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import com.haizhuo.brain.runtime.api.team.TeamExecutionPersistence.MemberEvent;
import com.haizhuo.brain.platform.run.DurableEventMetadata;
import com.haizhuo.brain.platform.run.EventVisibility;
import java.time.format.DateTimeFormatter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable parent-Run association and action budget for AgentScope native Team state. */
@Repository
public class JdbcTeamExecutionPersistence implements TeamExecutionPersistence {

    private final JdbcTemplate jdbc;
    private final JdbcRunEventAppender events;

    public JdbcTeamExecutionPersistence(JdbcTemplate jdbc, JdbcRunEventAppender events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional
    public void start(TeamExecutionSnapshot execution) {
        ParentRun parent = lockCurrentParent(execution);
        if (parent.employeeId() <= 0 || parent.definitionVersionId() != execution.ownerDefinitionVersionId())
            throw new IllegalStateException("Team lead does not match the frozen parent Run");
        if (execution.members().stream().anyMatch(member -> member.kind() == TeamExecutionMember.Kind.LEAD
                && member.employeeId() != 0 && member.employeeId() != parent.employeeId()))
            throw new IllegalStateException("Team lead employee does not match the parent Run");

        try {
            jdbc.update("INSERT INTO platform_run_team_execution(team_execution_id,run_id,user_id,session_id,"
                            + "attempt_id,fence_token,team_name,native_namespace,state,started_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,'RUNNING',?)",
                    execution.teamExecutionId(), execution.runId().value(), execution.userId().value(),
                    execution.sessionId().value(), execution.attemptId(), execution.fenceToken(),
                    execution.teamName(), execution.namespace(), Timestamp.from(execution.startedAt()));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("This parent Run already owns an autonomous Team execution");
        }

        for (TeamExecutionMember member : execution.members()) {
            long employeeId = member.kind() == TeamExecutionMember.Kind.LEAD
                    ? parent.employeeId() : member.employeeId();
            if (employeeId <= 0) throw new IllegalStateException("Team worker employee identity is invalid");
            jdbc.update("INSERT INTO platform_run_team_member_binding(team_execution_id,role_id,employee_id,"
                            + "definition_version_id,harness_session_key,member_kind,state) VALUES(?,?,?,?,?,?,?)",
                    execution.teamExecutionId(), member.roleId(), employeeId, member.definitionVersionId(),
                    member.harnessSessionKey(), member.kind().name(), "BOUND");
        }
    }

    @Override
    @Transactional
    public void reserveAction(TeamExecutionSnapshot execution, Action action, int count, int limit) {
        if (action == null || count < 1 || limit < 1) throw new IllegalArgumentException("invalid Team action budget");
        lockCurrentParent(execution);
        List<String> rows = jdbc.query("SELECT state FROM platform_run_team_execution "
                        + "WHERE team_execution_id=? AND run_id=? AND attempt_id=? AND fence_token=? FOR UPDATE",
                (rs, row) -> rs.getString(1), execution.teamExecutionId(), execution.runId().value(),
                execution.attemptId(), execution.fenceToken());
        if (rows.size() != 1 || !"RUNNING".equals(rows.get(0)))
            throw new IllegalStateException("Team action is outside its active execution generation");
        Integer used = jdbc.queryForObject("SELECT COALESCE(SUM(action_count),0) FROM platform_run_team_action_reservation "
                + "WHERE team_execution_id=? AND action_type=?", Integer.class,
                execution.teamExecutionId(), action.name());
        if ((used == null ? 0 : used) + count > limit)
            throw new IllegalStateException("Team action budget exhausted");
        jdbc.update("INSERT INTO platform_run_team_action_reservation(action_id,team_execution_id,action_type,"
                        + "action_count,reserved_at) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(), execution.teamExecutionId(), action.name(), count,
                Timestamp.from(Instant.now()));
    }

    @Override
    @Transactional
    public boolean complete(TeamExecutionSnapshot execution, String resultSha256) {
        if (resultSha256 == null || !resultSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Team result hash is invalid");
        lockCurrentParent(execution);
        return jdbc.update("UPDATE platform_run_team_execution SET state='COMPLETED',completed_at=?,result_sha256=? "
                        + "WHERE team_execution_id=? AND run_id=? AND attempt_id=? AND fence_token=? AND state='RUNNING'",
                Timestamp.from(Instant.now()), resultSha256, execution.teamExecutionId(), execution.runId().value(),
                execution.attemptId(), execution.fenceToken()) == 1;
    }

    @Override
    @Transactional
    public boolean recordMemberEvent(TeamExecutionSnapshot execution, String roleId, MemberEvent event,
                                    long ordinal, Instant occurredAt) {
        if (roleId == null || roleId.isBlank() || event == null || ordinal < 1 || occurredAt == null)
            throw new IllegalArgumentException("invalid Team member event");
        lockCurrentParent(execution);
        TeamExecutionMember member = execution.members().stream()
                .filter(candidate -> candidate.roleId().equals(roleId))
                .findFirst().orElseThrow(() -> new SecurityException("Team member is outside the frozen roster"));
        AgentEventDescriptor descriptor = new AgentEventDescriptor(
                "team:" + execution.teamExecutionId() + ":" + roleId + ":" + ordinal + ":" + event.name(),
                DateTimeFormatter.ISO_INSTANT.format(occurredAt), "TEAM_MEMBER_" + event.name(),
                member.harnessSessionKey(), null, null, null, member.harnessSessionKey(), null,
                execution.sessionId().value(), AgentExecutionRole.CHILD, execution.attemptId(), execution.fenceToken());
        events.append(execution.runId(), "INTERNAL_TEAM_MEMBER_" + event.name(), "", EventVisibility.INTERNAL,
                DurableEventMetadata.execution(descriptor), descriptor.nativeEventId(), occurredAt);
        return true;
    }

    @Override
    @Transactional
    public boolean requireRecovery(TeamExecutionSnapshot execution, String safeReasonCode) {
        if (safeReasonCode == null || !safeReasonCode.matches("[A-Z0-9_]{1,64}"))
            throw new IllegalArgumentException("Team recovery reason code is invalid");
        lockRunAndSession(execution);
        List<String> rows = jdbc.query("SELECT state FROM platform_agent_run WHERE run_id=? AND user_id=? "
                        + "AND session_id=? AND runtime_profile='TEAM_AUTONOMOUS_READONLY' "
                        + "AND state IN ('RUNNING','CANCELLING','RECOVERY_REQUIRED') FOR UPDATE",
                (rs, row) -> rs.getString(1), execution.runId().value(), execution.userId().value(),
                execution.sessionId().value());
        if (rows.size() != 1) return false;
        String runState = rows.get(0);
        Instant now = Instant.now();
        List<String> attempts = jdbc.query("SELECT state FROM platform_run_execution_attempt "
                        + "WHERE run_id=? AND attempt_id=? AND fence_token=? FOR UPDATE",
                (rs, row) -> rs.getString(1), execution.runId().value(), execution.attemptId(), execution.fenceToken());
        if (attempts.size() != 1 || !("RUNNING".equals(attempts.get(0))
                || "RECOVERY_REQUIRED".equals(attempts.get(0)))) return false;
        int teamUpdated = jdbc.update("UPDATE platform_run_team_execution SET state='RECOVERY_REQUIRED',completed_at=?,"
                        + "recovery_reason_code=? WHERE team_execution_id=? AND run_id=? AND attempt_id=? "
                        + "AND fence_token=? AND state='RUNNING'",
                Timestamp.from(now), safeReasonCode, execution.teamExecutionId(), execution.runId().value(),
                execution.attemptId(), execution.fenceToken());
        if (teamUpdated != 1) return false;
        if (!"RECOVERY_REQUIRED".equals(runState)) {
            if (jdbc.update("UPDATE platform_run_execution_attempt SET state='RECOVERY_REQUIRED',finished_at=? "
                            + "WHERE run_id=? AND attempt_id=? AND fence_token=? AND state='RUNNING'",
                    Timestamp.from(now), execution.runId().value(), execution.attemptId(), execution.fenceToken()) != 1)
                throw new IllegalStateException("Team attempt changed while entering recovery");
            if (jdbc.update("UPDATE platform_agent_run SET state='RECOVERY_REQUIRED',failure_code=? "
                            + "WHERE run_id=? AND state IN ('RUNNING','CANCELLING')",
                    safeReasonCode, execution.runId().value()) != 1)
                throw new IllegalStateException("Parent Run changed while Team entered recovery");
            events.append(execution.runId(), "RUN_RECOVERY_REQUIRED",
                    "自治 Team 执行结果不确定，需要管理员核查。", now);
        }
        return true;
    }

    private ParentRun lockCurrentParent(TeamExecutionSnapshot execution) {
        lockRunAndSession(execution);
        List<ParentRun> parents = jdbc.query("SELECT employee_id,definition_version_id FROM platform_agent_run "
                        + "WHERE run_id=? AND user_id=? AND session_id=? AND state='RUNNING' "
                        + "AND runtime_profile='TEAM_AUTONOMOUS_READONLY' FOR UPDATE",
                (rs, row) -> new ParentRun(rs.getLong(1), rs.getLong(2)),
                execution.runId().value(), execution.userId().value(), execution.sessionId().value());
        if (parents.size() != 1) throw new IllegalStateException("Team execution requires its active autonomous parent Run");
        Instant now = Instant.now();
        List<String> attempts = jdbc.query("SELECT attempt_id FROM platform_run_execution_attempt "
                        + "WHERE run_id=? AND attempt_id=? AND fence_token=? AND state='RUNNING' "
                        + "AND lease_expires_at>? FOR UPDATE",
                (rs, row) -> rs.getString(1),
                execution.runId().value(), execution.attemptId(), execution.fenceToken(), Timestamp.from(now));
        if (attempts.size() != 1) throw new IllegalStateException("Team execution has a stale parent Run fence");
        return parents.get(0);
    }

    private void lockRunAndSession(TeamExecutionSnapshot execution) {
        var sessionId = events.lockRun(execution.runId());
        if (sessionId == null || !sessionId.equals(execution.sessionId()))
            throw new IllegalStateException("Team execution is outside its parent Session");
    }

    private record ParentRun(long employeeId, long definitionVersionId) {}
}
