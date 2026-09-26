package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;

public record ToolApprovalRequiredEvent(RunId runId, String toolName, String reason) implements BrainAgentEvent {
}
