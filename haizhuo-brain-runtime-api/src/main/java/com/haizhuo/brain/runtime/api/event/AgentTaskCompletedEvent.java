package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.AgentTaskId;

public record AgentTaskCompletedEvent(AgentTaskId taskId, String result) implements BrainAgentEvent {
}
