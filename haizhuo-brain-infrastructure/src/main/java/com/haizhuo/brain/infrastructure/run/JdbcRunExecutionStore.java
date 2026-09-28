package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.infrastructure.session.JdbcSessionEventProjector;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.ExecutionAttemptState;
import com.haizhuo.brain.platform.run.ExecutionClaim;
import com.haizhuo.brain.platform.run.ExecutionResumeReason;
import com.haizhuo.brain.platform.run.HarnessRunSpec;
import com.haizhuo.brain.platform.run.RunExecutionAttempt;
import com.haizhuo.brain.platform.run.RunExecutionStore;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ToolApproval;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认领 / 租约 / 心跳 / fence 的 JDBC 适配器（规格 §13.2，V11 + run 行）。
 * 每个写入方都校验 attemptId + leaseToken + fenceToken + attempt 状态 RUNNING，
 * 因此租约已被回收的旧 worker 永远无法覆盖更新的 attempt（§13.6/I-11）。
 */
@Repository
public class JdbcRunExecutionStore implements RunExecutionStore {
    private final JdbcTemplate jdbc;
    private final JdbcSessionEventProjector sessionEvents;

    public JdbcRunExecutionStore(JdbcTemplate jdbc, JdbcSessionEventProjector sessionEvents) {
        this.jdbc = jdbc;
        this.sessionEvents = sessionEvents;
    }

    @Override
    @Transactional
    public Optional<ExecutionClaim> claimNext(String workerId, Duration leaseTtl) {
        Instant now = Instant.now();
        List<AgentRun> queued = jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,"
                        + "client_request_id,input_digest,state,created_at,started_at,finished_at FROM platform_agent_run r "
                        + "WHERE r.state='QUEUED' AND NOT EXISTS (SELECT 1 FROM platform_agent_run active "
                        + "WHERE active.session_id=r.session_id AND active.state IN ('RUNNING','CANCELLING')) "
                        + "ORDER BY r.created_at,r.run_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (rs, n) -> mapRun(rs));
        if (queued.isEmpty()) return Optional.empty();
        AgentRun run = queued.get(0);

        Integer nextAttempt = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0)+1 FROM platform_run_execution_attempt WHERE run_id=?",
                Integer.class, run.id().value());
        int attemptNo = nextAttempt == null ? 1 : nextAttempt;
        RunExecutionAttempt attempt = new RunExecutionAttempt(UUID.randomUUID().toString(), run.id(), attemptNo,
                workerId, UUID.randomUUID().toString(), attemptNo, now.plus(leaseTtl), now,
                ExecutionAttemptState.RUNNING, now, null);
        jdbc.update("INSERT INTO platform_run_execution_attempt(attempt_id,run_id,attempt_no,worker_id,lease_token,"
                        + "fence_token,lease_expires_at,heartbeat_at,state,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                attempt.attemptId(), run.id().value(), attemptNo, workerId, attempt.leaseToken(), attempt.fenceToken(),
                Timestamp.from(attempt.leaseExpiresAt()), Timestamp.from(now), ExecutionAttemptState.RUNNING.name(),
                Timestamp.from(now));
        jdbc.update("UPDATE platform_agent_run SET state='RUNNING',started_at=COALESCE(started_at,?) WHERE run_id=? AND state='QUEUED'",
                Timestamp.from(now), run.id().value());

        ExecutionResumeReason reason = deliverableResults(run.id()) ? ExecutionResumeReason.TOOL_RESULT_READY
                : ExecutionResumeReason.INITIAL_PROMPT;
        appendEvent(run.id(), "RUN_STARTED",
                reason == ExecutionResumeReason.TOOL_RESULT_READY ? "已从工具结果恢复执行" : "执行已开始", now);
        HarnessRunSpec runSpec = findRunSpec(run.id())
                .orElseThrow(() -> new IllegalStateException("Run spec is missing for run " + run.id().value()));
        return Optional.of(new ExecutionClaim(findRun(run.id()).orElseThrow(), runSpec, attempt, reason));
    }

    @Override
    public boolean heartbeat(String attemptId, String leaseToken, long fenceToken, Duration leaseTtl) {
        Instant now = Instant.now();
        return jdbc.update("UPDATE platform_run_execution_attempt SET lease_expires_at=?,heartbeat_at=? "
                        + "WHERE attempt_id=? AND lease_token=? AND fence_token=? AND state='RUNNING'",
                Timestamp.from(now.plus(leaseTtl)), Timestamp.from(now), attemptId, leaseToken, fenceToken) == 1;
    }

    @Override
    @Transactional
    public boolean suspendForTools(ExecutionClaim claim, List<PlatformToolExecution> executions,
                                   List<ToolApproval> approvals, RunState waitingState,
                                   List<String> deliveredToolExecutionIds) {
        Instant now = Instant.now();
        if (!finishAttempt(claim, ExecutionAttemptState.SUSPENDED, now)) {
            return false;
        }
        // Harness 在再次挂起前已经消费了这些恢复结果；
        // 一次性守卫的翻转与 complete 时完全一致（§45.1）。
        for (String toolExecutionId : deliveredToolExecutionIds) {
            jdbc.update("UPDATE platform_tool_execution SET result_delivery_state='DELIVERED' "
                            + "WHERE tool_execution_id=? AND result_delivery_state='READY'", toolExecutionId);
        }
        for (PlatformToolExecution execution : executions) {
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM platform_tool_execution WHERE run_id=? AND tool_use_id=?",
                    Integer.class, execution.runId().value(), execution.toolUseId());
            if (existing != null && existing > 0) {
                continue; // 幂等重复挂起：(run_id, tool_use_id) 唯一（§6.8）
            }
            jdbc.update("INSERT INTO platform_tool_execution(tool_execution_id,run_id,tool_use_id,capability_revision_id,"
                            + "tool_name,input_json,input_digest,state,idempotency_key,created_at,updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    execution.id(), execution.runId().value(), execution.toolUseId(), execution.capabilityRevisionId(),
                    execution.toolName(), execution.inputJson(), execution.inputDigest(), execution.state().name(),
                    execution.idempotencyKey(), Timestamp.from(execution.createdAt()), Timestamp.from(execution.updatedAt()));
            appendEvent(claim.run().id(), "TOOL_REQUESTED", "工具调用已受理：" + execution.toolName(), now);
        }
        for (ToolApproval approval : approvals) {
            jdbc.update("INSERT INTO platform_tool_approval(approval_id,tool_execution_id,run_id,requested_for_user_id,"
                            + "decision,created_at) VALUES(?,?,?,?,'PENDING',?)",
                    approval.id(), approval.toolExecutionId(), approval.runId().value(),
                    approval.requestedFor().value(), Timestamp.from(approval.createdAt()));
            appendEvent(claim.run().id(), "TOOL_APPROVAL_REQUIRED", "工具调用需要人工确认", now);
        }
        int waiting = jdbc.update("UPDATE platform_agent_run SET state=? WHERE run_id=? AND state='RUNNING'",
                waitingState.name(), claim.run().id().value());
        if (waiting != 1) {
            throw new IllegalStateException("Run is no longer running while suspending: " + claim.run().id().value());
        }
        return true;
    }

    @Override
    @Transactional
    public boolean complete(ExecutionClaim claim, String result, List<String> deliveredToolExecutionIds) {
        Instant now = Instant.now();
        if (!finishAttempt(claim, ExecutionAttemptState.SUCCEEDED, now)) {
            return false;
        }
        // 一次性投递守卫（§45.1/§46）：只有仍处于 READY 的结果才会翻转成 DELIVERED，
        // 因此崩溃后的重复恢复绝不会再次投递一个已消费的工具结果（I-12）。
        for (String toolExecutionId : deliveredToolExecutionIds) {
            jdbc.update("UPDATE platform_tool_execution SET result_delivery_state='DELIVERED' "
                            + "WHERE tool_execution_id=? AND result_delivery_state='READY'", toolExecutionId);
        }
        int finished = jdbc.update("UPDATE platform_agent_run SET state='SUCCEEDED',finished_at=? WHERE run_id=? AND state='RUNNING'",
                Timestamp.from(now), claim.run().id().value());
        if (finished != 1) {
            throw new IllegalStateException("Run is no longer running while completing: " + claim.run().id().value());
        }
        appendEvent(claim.run().id(), "RUN_COMPLETED", result, now);
        return true;
    }

    @Override
    @Transactional
    public boolean fail(ExecutionClaim claim, String errorCode, String safeMessage) {
        Instant now = Instant.now();
        if (!finishAttempt(claim, ExecutionAttemptState.FAILED, now)) {
            return false;
        }
        int finished = jdbc.update("UPDATE platform_agent_run SET state='FAILED',finished_at=?,failure_code=? "
                        + "WHERE run_id=? AND state='RUNNING'",
                Timestamp.from(now), errorCode, claim.run().id().value());
        if (finished != 1) {
            throw new IllegalStateException("Run is no longer running while failing: " + claim.run().id().value());
        }
        appendEvent(claim.run().id(), "RUN_FAILED", safeMessage, now);
        return true;
    }

    @Override
    @Transactional
    public boolean cancelled(ExecutionClaim claim, String safeMessage) {
        Instant now = Instant.now();
        if (!finishAttempt(claim, ExecutionAttemptState.CANCELLED, now)) {
            return false;
        }
        int finished = jdbc.update("UPDATE platform_agent_run SET state='CANCELLED',finished_at=? "
                        + "WHERE run_id=? AND state IN ('CANCELLING','RUNNING')",
                Timestamp.from(now), claim.run().id().value());
        if (finished != 1) {
            throw new IllegalStateException("Run is no longer cancellable: " + claim.run().id().value());
        }
        appendEvent(claim.run().id(), "RUN_CANCELLED", safeMessage, now);
        return true;
    }

    @Override
    @Transactional
    public int reclaimExpiredLeases() {
        Instant now = Instant.now();
        List<ExpiredAttempt> expired = jdbc.query("SELECT attempt_id,run_id FROM platform_run_execution_attempt "
                        + "WHERE state='RUNNING' AND lease_expires_at<? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new ExpiredAttempt(rs.getString("attempt_id"), rs.getString("run_id")),
                Timestamp.from(now));
        int reclaimed = 0;
        for (ExpiredAttempt attempt : expired) {
            int lost = jdbc.update("UPDATE platform_run_execution_attempt SET state='LEASE_LOST',finished_at=? "
                            + "WHERE attempt_id=? AND state='RUNNING'",
                    Timestamp.from(now), attempt.attemptId());
            if (lost != 1) continue;
            // §15：该 run 回到 QUEUED 以便至少一次地重新执行（I-12）；
            // 正在取消中的 run 则以 CANCELLED 落定，而不是重新排队。
            int requeued = jdbc.update("UPDATE platform_agent_run SET state='QUEUED' WHERE run_id=? AND state='RUNNING'",
                    attempt.runId());
            if (requeued == 1) {
                appendEvent(new RunId(attempt.runId()), "RUN_REQUEUED_AFTER_LEASE_TIMEOUT", "执行租约过期，运行已重新排队", now);
                reclaimed++;
                continue;
            }
            int cancelled = jdbc.update("UPDATE platform_agent_run SET state='CANCELLED',finished_at=? "
                            + "WHERE run_id=? AND state='CANCELLING'",
                    Timestamp.from(now), attempt.runId());
            if (cancelled == 1) {
                appendEvent(new RunId(attempt.runId()), "RUN_CANCELLED", "执行已取消", now);
                reclaimed++;
            }
        }
        return reclaimed;
    }

    @Transactional
    @Override public boolean recordPlanSnapshot(ExecutionClaim claim, String content) {
        // 只校验租约与 fence、不改尝试状态：过期 worker 不得把计划写进已被重新认领的运行。
        Integer owned = jdbc.query("SELECT 1 FROM platform_run_execution_attempt "
                        + "WHERE attempt_id=? AND lease_token=? AND fence_token=? AND state='RUNNING' FOR UPDATE",
                (rs, row) -> rs.getInt(1), claim.attempt().attemptId(), claim.attempt().leaseToken(),
                claim.attempt().fenceToken()).stream().findFirst().orElse(null);
        if (owned == null) {
            return false;
        }
        appendEvent(claim.run().id(), "PLAN_SNAPSHOT", content, Instant.now());
        return true;
    }

    /** 公共 fence 校验：只有当前租约持有者才能推进该 attempt（§13.6）。 */
    private boolean finishAttempt(ExecutionClaim claim, ExecutionAttemptState target, Instant now) {
        return jdbc.update("UPDATE platform_run_execution_attempt SET state=?,finished_at=? "
                        + "WHERE attempt_id=? AND lease_token=? AND fence_token=? AND state='RUNNING'",
                target.name(), Timestamp.from(now), claim.attempt().attemptId(), claim.attempt().leaseToken(),
                claim.attempt().fenceToken()) == 1;
    }

    private boolean deliverableResults(RunId runId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_tool_execution WHERE run_id=? "
                        + "AND result_delivery_state='READY' "
                        + "AND state IN ('SUCCEEDED','DENIED','FAILED','RECONCILIATION_REQUIRED')",
                Integer.class, runId.value());
        return count != null && count > 0;
    }

    private Optional<AgentRun> findRun(RunId runId) {
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,"
                        + "input_digest,state,created_at,started_at,finished_at FROM platform_agent_run WHERE run_id=?",
                (rs, n) -> mapRun(rs), runId.value()).stream().findFirst();
    }

    private Optional<HarnessRunSpec> findRunSpec(RunId runId) {
        return jdbc.query("SELECT run_id,definition_version_id,definition_bundle_id,definition_bundle_hash,"
                        + "model_visible_tool_names_json,tool_view_hash,effective_capability_hash,channel_type,created_at "
                        + "FROM platform_agent_run_spec WHERE run_id=?",
                (rs, n) -> new HarnessRunSpec(new RunId(rs.getString("run_id")), rs.getLong("definition_version_id"),
                        rs.getLong("definition_bundle_id"), rs.getString("definition_bundle_hash"),
                        rs.getString("model_visible_tool_names_json"), rs.getString("tool_view_hash"),
                        rs.getString("effective_capability_hash"), rs.getString("channel_type"),
                        rs.getTimestamp("created_at").toInstant()), runId.value()).stream().findFirst();
    }

    /** run 事件与会话投影必须在同一事务内写入，否则会话游标会出现空洞。 */
    private void appendEvent(RunId runId, String type, String content, Instant now) {
        jdbc.queryForObject("SELECT run_id FROM platform_agent_run WHERE run_id=? FOR UPDATE", String.class, runId.value());
        int sequence = nextSequence(runId);
        String safeContent = content != null && content.length() > 4000 ? content.substring(0, 4000) : content;
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) VALUES(?,?,?,?,?)",
                runId.value(), sequence, type, safeContent, Timestamp.from(now));
        sessionEvents.project(sessionIdOf(runId), runId, sequence, type, safeContent, now);
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

    private AgentRun mapRun(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new AgentRun(new RunId(rs.getString("run_id")), new SessionId(rs.getString("session_id")),
                new UserId(rs.getLong("user_id")), rs.getLong("employee_id"), rs.getLong("definition_version_id"),
                rs.getString("client_request_id"), rs.getString("input_digest"),
                RunState.valueOf(rs.getString("state")), rs.getTimestamp("created_at").toInstant(),
                startedAt == null ? null : startedAt.toInstant(), finishedAt == null ? null : finishedAt.toInstant());
    }

    private record ExpiredAttempt(String attemptId, String runId) { }
}
