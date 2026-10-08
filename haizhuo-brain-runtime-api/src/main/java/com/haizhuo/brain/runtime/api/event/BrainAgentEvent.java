package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;

public interface BrainAgentEvent {
    RunId runId();

    /** 平台合成/旧事件没有原生描述，不能据此虚构原生来源。 */
    default AgentEventDescriptor descriptor() {
        return null;
    }
}
