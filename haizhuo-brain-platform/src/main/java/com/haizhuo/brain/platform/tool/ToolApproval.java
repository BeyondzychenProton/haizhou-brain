package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;
import java.util.Objects;

/** 工具执行的持久化审批事实（规格 §39.1）。每次执行至多一条审批。 */
public record ToolApproval(String id, String toolExecutionId, RunId runId, UserId requestedFor,
                           ApprovalState decision, UserId decidedBy, String reason,
                           Instant createdAt, Instant decidedAt) {
    public ToolApproval {
        Objects.requireNonNull(id);
        Objects.requireNonNull(toolExecutionId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(requestedFor);
        Objects.requireNonNull(decision);
        Objects.requireNonNull(createdAt);
    }
}
