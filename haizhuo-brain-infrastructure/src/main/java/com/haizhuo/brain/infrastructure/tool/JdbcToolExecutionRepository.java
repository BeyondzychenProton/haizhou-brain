package com.haizhuo.brain.infrastructure.tool;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.tool.ClaimedToolExecution;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ResultDeliveryState;
import com.haizhuo.brain.platform.tool.ToolExecutionRepository;
import com.haizhuo.brain.platform.tool.ToolExecutionState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 持久化工具执行的 JDBC 适配器（规格 §55.1）。结果完成走 Tx-05：
 * 终态 + 结果 READY + 用户可见事件 + run 重新排队原子地发生，
 * 因此 Harness 恢复时绝不会看到一个写了一半的结果（I-12）。
 */
@Repository
public class JdbcToolExecutionRepository implements ToolExecutionRepository {
    private static final String COLUMNS = "tool_execution_id,run_id,tool_use_id,capability_revision_id,tool_name,"
            + "input_json,input_digest,state,idempotency_key,external_receipt,result_json,result_digest,"
            + "result_delivery_state,error_code,created_at,updated_at";

    private final JdbcTemplate jdbc;

    public JdbcToolExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlatformToolExecution> findById(String toolExecutionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_tool_execution WHERE tool_execution_id=?",
                (rs, n) -> map(rs), toolExecutionId).stream().findFirst();
    }

    @Override
    public Optional<PlatformToolExecution> findByRunAndToolUseId(RunId runId, String toolUseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_tool_execution WHERE run_id=? AND tool_use_id=?",
                (rs, n) -> map(rs), runId.value(), toolUseId).stream().findFirst();
    }

    @Override
    public List<PlatformToolExecution> findByRun(RunId runId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_tool_execution WHERE run_id=? ORDER BY created_at,tool_execution_id",
                (rs, n) -> map(rs), runId.value());
    }

    @Override
    public List<PlatformToolExecution> findDeliverableResults(RunId runId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_tool_execution WHERE run_id=?"
                        + " AND result_delivery_state='READY' AND state IN ('SUCCEEDED','DENIED','FAILED','RECONCILIATION_REQUIRED')"
                        + " ORDER BY created_at,tool_execution_id",
                (rs, n) -> map(rs), runId.value());
    }

    @Override
    @Transactional
    public Optional<ClaimedToolExecution> claimNextExecutable(String workerId) {
        List<ClaimedToolExecution> claimed = jdbc.query(
                "SELECT e." + COLUMNS.replace(",", ",e.") + ","
                        + "r.session_id,r.user_id,r.employee_id,r.definition_version_id,r.client_request_id,"
                        + "r.input_digest,r.state run_state,r.created_at run_created_at,r.started_at,r.finished_at "
                        + "FROM platform_tool_execution e JOIN platform_agent_run r ON r.run_id=e.run_id "
                        + "WHERE e.state IN ('REQUESTED','APPROVED') ORDER BY e.created_at,e.tool_execution_id LIMIT 1 "
                        + "FOR UPDATE SKIP LOCKED",
                (rs, n) -> new ClaimedToolExecution(map(rs), mapRun(rs)));
        if (claimed.isEmpty()) return Optional.empty();
        PlatformToolExecution execution = claimed.get(0).execution();
        Instant now = Instant.now();
        jdbc.update("UPDATE platform_tool_execution SET state='EXECUTING',updated_at=? WHERE tool_execution_id=? AND state IN ('REQUESTED','APPROVED')",
                Timestamp.from(now), execution.id());
        return findById(execution.id()).map(updated -> new ClaimedToolExecution(updated, claimed.get(0).run()));
    }

    @Override
    @Transactional
    public void completeExecution(PlatformToolExecution outcome) {
        Instant now = Instant.now();
        int updated = jdbc.update("UPDATE platform_tool_execution SET state=?,result_json=?,result_digest=?,"
                        + "result_delivery_state='READY',error_code=?,external_receipt=?,updated_at=? "
                        + "WHERE tool_execution_id=? AND state='EXECUTING'",
                outcome.state().name(), outcome.resultJson(), outcome.resultDigest(), outcome.errorCode(),
                outcome.externalReceipt(), Timestamp.from(now), outcome.id());
        if (updated != 1) {
            throw new IllegalStateException("Tool execution is no longer executing: " + outcome.id());
        }
        appendEvent(outcome.runId(), "TOOL_RESULT_READY", "工具调用已完成：" + outcome.toolName(), now);
        requeueRunIfSettled(outcome.runId());
    }

    @Override
    @Transactional
    public void requeueRunIfSettled(RunId runId) {
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM platform_tool_execution WHERE run_id=? AND state IN ('REQUESTED','APPROVAL_REQUIRED','APPROVED','EXECUTING')",
                Integer.class, runId.value());
        if (open != null && open == 0) {
            Instant now = Instant.now();
            int requeued = jdbc.update("UPDATE platform_agent_run SET state='QUEUED' WHERE run_id=? AND state IN ('WAITING_TOOL','WAITING_CONFIRMATION')",
                    runId.value());
            if (requeued == 1) {
                appendEvent(runId, "RUN_RESUME_QUEUED", "运行已恢复排队", now);
            }
        }
    }

    void appendEvent(RunId runId, String type, String content, Instant now) {
        jdbc.queryForObject("SELECT run_id FROM platform_agent_run WHERE run_id=? FOR UPDATE", String.class, runId.value());
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) "
                        + "SELECT ?,COALESCE(MAX(sequence_no),0)+1,?,?,? FROM platform_agent_run_event WHERE run_id=?",
                runId.value(), type, content, Timestamp.from(now), runId.value());
    }

    private PlatformToolExecution map(ResultSet rs) throws SQLException {
        String delivery = rs.getString("result_delivery_state");
        return new PlatformToolExecution(rs.getString("tool_execution_id"), new RunId(rs.getString("run_id")),
                rs.getString("tool_use_id"), rs.getLong("capability_revision_id"), rs.getString("tool_name"),
                rs.getString("input_json"), rs.getString("input_digest"),
                ToolExecutionState.valueOf(rs.getString("state")), rs.getString("idempotency_key"),
                rs.getString("external_receipt"), rs.getString("result_json"), rs.getString("result_digest"),
                delivery == null ? null : ResultDeliveryState.valueOf(delivery), rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private AgentRun mapRun(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new AgentRun(new RunId(rs.getString("run_id")), new SessionId(rs.getString("session_id")),
                new UserId(rs.getLong("user_id")), rs.getLong("employee_id"), rs.getLong("definition_version_id"),
                rs.getString("client_request_id"), rs.getString("input_digest"),
                RunState.valueOf(rs.getString("run_state")),
                rs.getTimestamp("run_created_at").toInstant(),
                startedAt == null ? null : startedAt.toInstant(), finishedAt == null ? null : finishedAt.toInstant());
    }
}
