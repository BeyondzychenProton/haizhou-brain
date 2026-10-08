package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;

/** 运行时在持久化的取消请求之后抵达了安全检查点。 */
public record AgentRunCancelledEvent(RunId runId, String message,
                                     AgentEventDescriptor descriptor) implements BrainAgentEvent {
    public AgentRunCancelledEvent(RunId runId, String message) {
        this(runId, message, null);
    }
}
