package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.AgentTaskId;

public record AgentTextDeltaEvent(AgentTaskId taskId, String text) implements BrainAgentEvent {
}
