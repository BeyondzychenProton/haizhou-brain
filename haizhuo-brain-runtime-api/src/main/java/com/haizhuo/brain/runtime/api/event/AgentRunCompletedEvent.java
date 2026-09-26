package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;

public record AgentRunCompletedEvent(RunId runId, String result) implements BrainAgentEvent {
}
