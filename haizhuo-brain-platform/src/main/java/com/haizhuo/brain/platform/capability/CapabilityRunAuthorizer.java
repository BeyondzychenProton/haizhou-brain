package com.haizhuo.brain.platform.capability;

import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;

/** Tool 的执行前复核边界；不能只信任模型可见工具清单。 */
public interface CapabilityRunAuthorizer {
    boolean isAllowedNow(AgentExecutionRequest run, String capabilityCode);
}
