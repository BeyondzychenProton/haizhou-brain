package com.haizhuo.brain.runtime.agentscope.tool;

import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeCapability;
import io.agentscope.core.tool.AgentTool;

/** 将已登记的能力修订映射为 AgentScope 工具；数据库不能自行指定此接口的实现类。 */
public interface RuntimeToolProvider {
    String implementationKey();
    boolean supports(String capabilityCode);
    AgentTool create(RuntimeCapability capability, AgentExecutionRequest run, ToolExecutionGateway gateway);
}
