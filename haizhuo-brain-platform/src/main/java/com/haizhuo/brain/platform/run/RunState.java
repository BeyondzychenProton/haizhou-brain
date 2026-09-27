package com.haizhuo.brain.platform.run;

/**
 * 活动状态占用 Session 的执行槽位；终态释放该槽位（规格 §12.1）。
 * WAITING_TOOL / WAITING_CONFIRMATION 仍占用会话槽位，但不持有 worker 租约（§12.2/§13）。
 */
public enum RunState {
    QUEUED, RUNNING, WAITING_TOOL, WAITING_CONFIRMATION, CANCELLING, SUCCEEDED, FAILED, CANCELLED, EXPIRED;

    public boolean active() {
        return this == QUEUED || this == RUNNING || this == WAITING_TOOL
                || this == WAITING_CONFIRMATION || this == CANCELLING;
    }
}
