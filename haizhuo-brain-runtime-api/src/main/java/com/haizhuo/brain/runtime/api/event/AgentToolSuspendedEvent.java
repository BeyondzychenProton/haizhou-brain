package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;
import java.util.List;
import java.util.Objects;

/**
 * Harness 因外部（仅 Schema）工具调用而挂起时发出的事件（规格 §33/§34）。
 * 平台负责持久化这些调用、释放 worker 租约，并在工具结果落库后恢复该 Run。
 */
public record AgentToolSuspendedEvent(RunId runId, String replyId,
                                      List<PendingExternalToolCall> toolCalls) implements BrainAgentEvent {
    public AgentToolSuspendedEvent {
        Objects.requireNonNull(runId);
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls));
        if (toolCalls.isEmpty()) throw new IllegalArgumentException("toolCalls must not be empty");
    }
}
