package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;

public record AgentRunFailedEvent(RunId runId, String message) implements BrainAgentEvent {
}
