package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;

/**
 * 平台运行循环向实时投递层发布瞬时事件的最小端口。实现不得把投递成功当作 Run
 * 完成条件，也不得在这里持久化业务状态。
 */
public interface RunRealtimeEventPublisher {
    RunRealtimeEventPublisher NOOP = (runId, attemptId, streamOffset, text, occurredAt) -> { };

    void publishTextDelta(RunId runId, String attemptId, long streamOffset, String text, Instant occurredAt);
}
