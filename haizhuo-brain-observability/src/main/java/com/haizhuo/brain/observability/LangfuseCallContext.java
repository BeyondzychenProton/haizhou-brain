package com.haizhuo.brain.observability;

import com.haizhuo.brain.kernel.identity.RunId;
import java.util.concurrent.atomic.AtomicLong;

/** 单次 AgentScope 调用的非持久化观测上下文，放入 AgentScope RuntimeContext。 */
public final class LangfuseCallContext {
    private final String traceId;
    private final String runId;
    private final String sessionId;
    private final String userId;
    private final String attemptId;
    private final AtomicLong sequence = new AtomicLong();
    private volatile String agentObservationId;

    public LangfuseCallContext(RunId runId, String sessionId, String userId, String attemptId) {
        this.traceId = LangfuseTraceIds.forRun(runId);
        this.runId = runId.value();
        this.sessionId = sessionId;
        this.userId = userId;
        this.attemptId = attemptId;
    }

    public String traceId() { return traceId; }
    public String runId() { return runId; }
    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String attemptId() { return attemptId; }
    public String agentObservationId() { return agentObservationId; }
    public void agentObservationId(String value) { this.agentObservationId = value; }

    public String nextObservationId(String kind) {
        return LangfuseTraceIds.spanIdFor(traceId,
                attemptId + ":" + kind + ":" + sequence.incrementAndGet());
    }

    public String parentSpanId() {
        return LangfuseTraceIds.spanIdFor(traceId, attemptId + ":run-parent");
    }
}
