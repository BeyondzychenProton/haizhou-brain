package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.AgentTaskId;

public record AgentTaskFailedEvent(AgentTaskId taskId, String message) implements BrainAgentEvent {
}
