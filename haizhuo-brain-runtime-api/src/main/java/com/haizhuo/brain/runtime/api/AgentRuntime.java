package com.haizhuo.brain.runtime.api;

import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import reactor.core.publisher.Flux;

public interface AgentRuntime {
    Flux<BrainAgentEvent> execute(AgentExecutionRequest request);
}
