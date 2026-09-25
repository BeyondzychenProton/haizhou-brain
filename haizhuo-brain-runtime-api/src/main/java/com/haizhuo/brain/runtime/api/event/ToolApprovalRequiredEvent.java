package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.AgentTaskId;

public record ToolApprovalRequiredEvent(AgentTaskId taskId, String toolName, String reason) implements BrainAgentEvent {
}
