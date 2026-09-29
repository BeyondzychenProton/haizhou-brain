package com.haizhuo.brain.runtime.api;

import java.time.Instant;

/**
 * 平台工具执行完成后的观测端口。实现方不得参与授权、恢复或业务状态落定；
 * 观测不可用时也不能阻断主执行链。
 */
public interface RunObservationSink {

    void onToolExecution(String runId, String sessionId, String userId,
                         String executionId, String toolName,
                         long capabilityRevisionId, String state, String errorCode,
                         String inputDigest, Instant startedAt, Instant finishedAt);

    static RunObservationSink noop() {
        return (runId, sessionId, userId, executionId, toolName, capabilityRevisionId, state, errorCode,
                inputDigest, startedAt, finishedAt) -> { };
    }
}
