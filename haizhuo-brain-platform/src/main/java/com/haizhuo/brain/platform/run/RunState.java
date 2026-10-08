package com.haizhuo.brain.platform.run;

/**
 * 活动状态占用 Session 的执行槽位；终态释放该槽位（规格 §12.1）。
 * WAITING_* 与 RECOVERY_REQUIRED 占槽但不持有 worker 租约；RECOVERY_REQUIRED 只能由管理员审计处置。
 */
public enum RunState {
    QUEUED, RUNNING, WAITING_TOOL, WAITING_CONFIRMATION, CANCELLING, RECOVERY_REQUIRED,
    SUCCEEDED, FAILED, CANCELLED, TERMINATED, EXPIRED;

    public boolean active() {
        return this == QUEUED || this == RUNNING || this == WAITING_TOOL
                || this == WAITING_CONFIRMATION || this == CANCELLING || this == RECOVERY_REQUIRED;
    }
}
