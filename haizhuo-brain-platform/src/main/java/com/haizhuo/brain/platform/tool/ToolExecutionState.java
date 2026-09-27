package com.haizhuo.brain.platform.tool;

/**
 * 工具执行的持久化生命周期（规格 §6.9）。增设 CANCELLED 是为了支持 §47 的取消：
 * 表示一次“已请求但尚未开始”、且在副作用离开平台之前就被取消的执行。
 */
public enum ToolExecutionState {
    REQUESTED,
    APPROVAL_REQUIRED,
    APPROVED,
    EXECUTING,
    SUCCEEDED,
    DENIED,
    FAILED,
    RECONCILIATION_REQUIRED,
    CANCELLED;

    public boolean open() {
        return this == REQUESTED || this == APPROVAL_REQUIRED || this == APPROVED || this == EXECUTING;
    }

    public boolean terminal() {
        return !open();
    }
}
