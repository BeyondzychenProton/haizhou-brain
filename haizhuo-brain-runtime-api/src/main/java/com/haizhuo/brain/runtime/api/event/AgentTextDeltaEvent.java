package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;

public record AgentTextDeltaEvent(RunId runId, String text,
                                  AgentEventDescriptor descriptor) implements BrainAgentEvent {
    public AgentTextDeltaEvent(RunId runId, String text) {
        this(runId, text, null);
    }
}
