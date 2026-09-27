package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;

/** Runtime reached a safe checkpoint after a persisted cancellation request. */
public record AgentRunCancelledEvent(RunId runId, String message) implements BrainAgentEvent { }
