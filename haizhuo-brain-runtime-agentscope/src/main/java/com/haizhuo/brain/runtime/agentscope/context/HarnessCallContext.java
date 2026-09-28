package com.haizhuo.brain.runtime.agentscope.context;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.model.RuntimeRunConstraints;
import java.util.Objects;

/**
 * 承载在 AgentScope RuntimeContext 内部的调用级执行上下文（规格 §24）。
 * 它不会持久化进 AgentState，默认也不对模型可见。
 * 平台尚未为该绑定生成桥接快照时，bridgeContext 可能为 null。
 */
public record HarnessCallContext(RunId runId, String attemptId, long fenceToken,
                                 String workspaceRuntimeKey, RuntimeRunConstraints constraints,
                                 String bridgeContext) {
    public HarnessCallContext {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(attemptId);
        if (attemptId.isBlank()) throw new IllegalArgumentException("attemptId must not be blank");
        Objects.requireNonNull(workspaceRuntimeKey);
        if (workspaceRuntimeKey.isBlank()) throw new IllegalArgumentException("workspaceRuntimeKey must not be blank");
        Objects.requireNonNull(constraints);
    }
}
