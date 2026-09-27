package com.haizhuo.brain.platform.tool;

/** 审批生命周期（规格 §39）。PENDING 等待决定；CANCELLED 表示未做决定即关闭（§47.4）。 */
public enum ApprovalState {
    PENDING, APPROVED, REJECTED, CANCELLED
}
