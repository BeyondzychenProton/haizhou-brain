package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;

public interface BrainAgentEvent {
    RunId runId();
}
