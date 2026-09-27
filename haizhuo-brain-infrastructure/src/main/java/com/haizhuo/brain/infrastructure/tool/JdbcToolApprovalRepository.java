package com.haizhuo.brain.infrastructure.tool;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.tool.ApprovalState;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ToolApproval;
import com.haizhuo.brain.platform.tool.ToolApprovalRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具审批的 JDBC 适配器（规格 §55.2/§39）。审批决定是由 PENDING 状态守卫的一次性事实，
 * 因此重复审批只是幂等空操作（§84）。拒绝时写入一条对模型安全的结果并置为可投递——
 * 内部策略绝不外泄（§39.3）。
 */
@Repository
public class JdbcToolApprovalRepository implements ToolApprovalRepository {
    static final String DENIED_SAFE_MESSAGE = "该操作未获得授权/确认。";

    private final JdbcTemplate jdbc;

    public JdbcToolApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ToolApproval> findByToolExecutionId(String toolExecutionId) {
        return jdbc.query("SELECT approval_id,tool_execution_id,run_id,requested_for_user_id,decision,"
                        + "decided_by_user_id,reason,created_at,decided_at FROM platform_tool_approval "
                        + "WHERE tool_execution_id=?",
                (rs, n) -> map(rs), toolExecutionId).stream().findFirst();
    }

    @Override
    @Transactional
    public void createPending(PlatformToolExecution execution, long requestedForUserId) {
        Instant now = Instant.now();
        int updated = jdbc.update("UPDATE platform_tool_execution SET state='APPROVAL_REQUIRED',updated_at=? "
                        + "WHERE tool_execution_id=? AND state IN ('REQUESTED','EXECUTING')",
                Timestamp.from(now), execution.id());
        if (updated != 1) {
            throw new IllegalStateException("Tool execution cannot be parked for approval: " + execution.id());
        }
        jdbc.update("INSERT INTO platform_tool_approval(approval_id,tool_execution_id,run_id,requested_for_user_id,"
                        + "decision,created_at) VALUES(?,?,?,?,'PENDING',?)",
                UUID.randomUUID().toString(), execution.id(), execution.runId().value(), requestedForUserId,
                Timestamp.from(now));
        int waiting = jdbc.update("UPDATE platform_agent_run SET state='WAITING_CONFIRMATION' WHERE run_id=? AND state='WAITING_TOOL'",
                execution.runId().value());
        if (waiting == 1) {
            appendEvent(execution.runId(), "TOOL_APPROVAL_REQUIRED", "工具调用需要人工确认：" + execution.toolName(), now);
        }
    }

    @Override
    @Transactional
    public boolean decide(String toolExecutionId, ApprovalState decision, long decidedByUserId, String reason) {
        Instant now = Instant.now();
        Optional<ToolApproval> pending = jdbc.query("SELECT approval_id,tool_execution_id,run_id,requested_for_user_id,"
                        + "decision,decided_by_user_id,reason,created_at,decided_at FROM platform_tool_approval "
                        + "WHERE tool_execution_id=? FOR UPDATE",
                (rs, n) -> map(rs), toolExecutionId).stream().findFirst();
        if (pending.isEmpty() || pending.get().decision() != ApprovalState.PENDING) {
            return false;
        }
        ToolApproval approval = pending.get();
        jdbc.update("UPDATE platform_tool_approval SET decision=?,decided_by_user_id=?,reason=?,decided_at=? "
                        + "WHERE approval_id=? AND decision='PENDING'",
                decision.name(), decidedByUserId, reason, Timestamp.from(now), approval.id());
        if (decision == ApprovalState.APPROVED) {
            jdbc.update("UPDATE platform_tool_execution SET state='APPROVED',updated_at=? "
                            + "WHERE tool_execution_id=? AND state='APPROVAL_REQUIRED'",
                    Timestamp.from(now), toolExecutionId);
            jdbc.update("UPDATE platform_agent_run SET state='WAITING_TOOL' WHERE run_id=? AND state='WAITING_CONFIRMATION'",
                    approval.runId().value());
            appendEvent(approval.runId(), "TOOL_APPROVED", "工具调用已批准", now);
            return true;
        }
        // 拒绝：持久化安全文案并置为可投递，让 Harness 用自然语言把该 run 收尾（§39.3）。
        String resultJson = CanonicalJson.write(Map.of("success", false, "content", DENIED_SAFE_MESSAGE));
        jdbc.update("UPDATE platform_tool_execution SET state='DENIED',result_json=?,result_digest=?,"
                        + "result_delivery_state='READY',error_code='APPROVAL_DENIED',updated_at=? "
                        + "WHERE tool_execution_id=? AND state='APPROVAL_REQUIRED'",
                resultJson, CanonicalJson.sha256Hex(resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Timestamp.from(now), toolExecutionId);
        appendEvent(approval.runId(), "TOOL_REJECTED", "工具调用已被拒绝", now);
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM platform_tool_execution WHERE run_id=? "
                        + "AND state IN ('REQUESTED','APPROVAL_REQUIRED','APPROVED','EXECUTING')",
                Integer.class, approval.runId().value());
        if (open != null && open == 0) {
            int requeued = jdbc.update("UPDATE platform_agent_run SET state='QUEUED' WHERE run_id=? AND state IN ('WAITING_TOOL','WAITING_CONFIRMATION')",
                    approval.runId().value());
            if (requeued == 1) {
                appendEvent(approval.runId(), "RUN_RESUME_QUEUED", "运行已恢复排队", now);
            }
        }
        return true;
    }

    private void appendEvent(RunId runId, String type, String content, Instant now) {
        jdbc.queryForObject("SELECT run_id FROM platform_agent_run WHERE run_id=? FOR UPDATE", String.class, runId.value());
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) "
                        + "SELECT ?,COALESCE(MAX(sequence_no),0)+1,?,?,? FROM platform_agent_run_event WHERE run_id=?",
                runId.value(), type, content, Timestamp.from(now), runId.value());
    }

    private ToolApproval map(ResultSet rs) throws SQLException {
        // 先读完每一列再构造记录：wasNull() 只描述最近一次读取，
        // 因此必须在触碰 requested_for_user_id 之前把它取出来。
        long requestedFor = rs.getLong("requested_for_user_id");
        long decidedBy = rs.getLong("decided_by_user_id");
        boolean decidedByNull = rs.wasNull();
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new ToolApproval(rs.getString("approval_id"), rs.getString("tool_execution_id"),
                new RunId(rs.getString("run_id")), new UserId(requestedFor),
                ApprovalState.valueOf(rs.getString("decision")),
                decidedByNull ? null : new UserId(decidedBy), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(), decidedAt == null ? null : decidedAt.toInstant());
    }
}
