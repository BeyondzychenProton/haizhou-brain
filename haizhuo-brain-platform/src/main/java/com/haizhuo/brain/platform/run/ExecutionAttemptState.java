package com.haizhuo.brain.platform.run;

/** 物理尝试的生命周期（规格 §6.7）。LEASE_LOST 标记被已失效 worker 遗弃的尝试。 */
public enum ExecutionAttemptState {
    RUNNING, SUSPENDED, SUCCEEDED, FAILED, CANCELLED, LEASE_LOST, RECOVERY_REQUIRED
}
