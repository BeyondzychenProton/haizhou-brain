package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.SessionRunStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 审批应用服务（规格 §39）。可见性按已认证属主校验，但决定本身由持久化流水线授权——
 * 可见性绝不是授权（I-07）。重复或迟到的决定由仓储返回 {@code false} 拒绝（§84）。
 */
public class ToolApprovalService {
    private final SessionRunStore runs;
    private final ToolExecutionRepository executions;
    private final ToolApprovalRepository approvals;

    public ToolApprovalService(SessionRunStore runs, ToolExecutionRepository executions,
                               ToolApprovalRepository approvals) {
        this.runs = Objects.requireNonNull(runs);
        this.executions = Objects.requireNonNull(executions);
        this.approvals = Objects.requireNonNull(approvals);
    }

    /** 列出已认证用户所拥有的某个 Run 下的工具执行（规格 §39.2 GET）。 */
    public List<PlatformToolExecution> listForRun(RunId runId, UserId owner) {
        runs.findRun(runId, owner)
                .orElseThrow(() -> new IllegalArgumentException("Run was not found"));
        return executions.findByRun(runId);
    }

    public Optional<ToolApproval> approvalOf(String toolExecutionId) {
        return approvals.findByToolExecutionId(toolExecutionId);
    }

    /**
     * 记录属主的决定（规格 §39.2 POST）。审批已被决定时返回 false——
     * 调用方据此映射为幂等的 200/409，绝不演变成重试风暴。
     */
    public boolean decide(RunId runId, String toolExecutionId, UserId owner,
                          boolean approve, String reason) {
        runs.findRun(runId, owner)
                .orElseThrow(() -> new IllegalArgumentException("Run was not found"));
        PlatformToolExecution execution = executions.findById(toolExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("Tool execution was not found"));
        if (!execution.runId().equals(runId)) {
            throw new IllegalArgumentException("Tool execution does not belong to the run");
        }
        return approvals.decide(toolExecutionId,
                approve ? ApprovalState.APPROVED : ApprovalState.REJECTED,
                owner.value(), reason);
    }
}
