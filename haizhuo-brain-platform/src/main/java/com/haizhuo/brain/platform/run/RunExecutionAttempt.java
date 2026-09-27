package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;
import java.util.Objects;

/**
 * 一个 durable Run 的一次物理执行尝试（规格 §6.6）。
 * leaseToken 标识持有它的 worker；fenceToken 在同一 Run 内单调递增，
 * 用于拒绝过期的写入者（§13.6）。
 */
public record RunExecutionAttempt(String attemptId, RunId runId, int attemptNo, String workerId,
                                  String leaseToken, long fenceToken, Instant leaseExpiresAt,
                                  Instant heartbeatAt, ExecutionAttemptState state,
                                  Instant startedAt, Instant finishedAt) {
    public RunExecutionAttempt {
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(workerId);
        Objects.requireNonNull(leaseToken);
        Objects.requireNonNull(leaseExpiresAt);
        Objects.requireNonNull(heartbeatAt);
        Objects.requireNonNull(state);
        Objects.requireNonNull(startedAt);
    }
}
