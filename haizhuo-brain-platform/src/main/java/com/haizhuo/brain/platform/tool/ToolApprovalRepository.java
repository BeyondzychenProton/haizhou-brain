package com.haizhuo.brain.platform.tool;

import java.util.Optional;

/** 工具审批的持久化边界（规格 §57.4）。 */
public interface ToolApprovalRepository {
    Optional<ToolApproval> findByToolExecutionId(String toolExecutionId);

    /**
     * 把已认领的执行停靠下来等待人工确认（规格 §39）：执行置为 APPROVAL_REQUIRED +
     * 审批置为 PENDING + Run 置为 WAITING_CONFIRMATION，原子完成。
     * 用于审批策略在初始挂起路径之外触发的场景。
     */
    void createPending(PlatformToolExecution execution, long requestedForUserId);

    /**
     * 原子地记录决定：审批落定 + 执行置为 APPROVED 或 DENIED
     * （附带安全的拒绝结果）+ 事件；当所有执行都已落定时重新入队该 Run。
     * 审批已被决定或不存在时返回 false（§84 重复审批）。
     */
    boolean decide(String toolExecutionId, ApprovalState decision, long decidedByUserId, String reason);
}
