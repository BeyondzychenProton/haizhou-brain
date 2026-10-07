package com.haizhuo.brain.runtime.agentscope.context;

import com.haizhuo.brain.observability.LangfuseCallContext;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import io.agentscope.core.agent.RuntimeContext;

/**
 * 构建每次调用的 RuntimeContext（规格 §23）。userId 是真实的平台用户 ID，
 * 新会话 sessionId 就是业务 SessionId；存量会话才使用旧 harness 会话键。
 * 无状态，可跨模板安全共享。
 */
public class RuntimeContextFactory {

    public RuntimeContext create(AgentExecutionRequest request) {
        HarnessCallContext call = new HarnessCallContext(
                request.runId(),
                request.attemptId(),
                request.fenceToken(),
                request.binding().workspaceRuntimeKey(),
                request.constraints(),
                request.binding().bridgeContext());
        return RuntimeContext.builder()
                .userId(Long.toString(request.userId().value()))
                .sessionId(request.binding().bridgeSnapshotHash() == null
                        ? request.platformSessionId().value() : request.binding().harnessSessionKey())
                .put(HarnessCallContext.class, call)
                .put(LangfuseCallContext.class, new LangfuseCallContext(
                        request.runId(), request.platformSessionId().value(),
                        Long.toString(request.userId().value()), request.attemptId()))
                .build();
    }
}
