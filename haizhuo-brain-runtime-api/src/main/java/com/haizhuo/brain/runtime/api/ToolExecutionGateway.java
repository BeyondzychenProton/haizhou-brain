package com.haizhuo.brain.runtime.api;

import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import java.util.Map;

/** Platform controlled execution boundary for tools exposed to an agent. */
public interface ToolExecutionGateway {
    String execute(AgentExecutionRequest run, String capabilityId, Map<String, Object> arguments);
    default void finish(AgentExecutionRequest run) { }
}
