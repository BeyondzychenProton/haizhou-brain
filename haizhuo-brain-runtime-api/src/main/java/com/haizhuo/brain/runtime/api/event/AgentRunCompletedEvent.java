package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;

public record AgentRunCompletedEvent(RunId runId, String result,
                                     AgentEventDescriptor descriptor) implements BrainAgentEvent {
    public AgentRunCompletedEvent(RunId runId, String result) {
        this(runId, result, null);
    }
}
