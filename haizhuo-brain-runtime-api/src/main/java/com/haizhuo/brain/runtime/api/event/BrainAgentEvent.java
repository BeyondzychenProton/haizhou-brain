package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.AgentTaskId;

public interface BrainAgentEvent {
    AgentTaskId taskId();
}
