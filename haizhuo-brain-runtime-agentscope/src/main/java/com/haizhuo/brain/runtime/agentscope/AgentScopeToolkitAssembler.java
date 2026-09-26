package com.haizhuo.brain.runtime.agentscope;

import com.haizhuo.brain.runtime.agentscope.tool.CapabilityAdapterRegistry;
import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeCapability;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 仅把平台计算并冻结的有效能力集合装入本 Run 的 Toolkit。 */
@Component
@Profile("meeting-mock | test")
public class AgentScopeToolkitAssembler {
    private final CapabilityAdapterRegistry registry;

    public AgentScopeToolkitAssembler(CapabilityAdapterRegistry registry) { this.registry = registry; }

    public Toolkit assemble(AgentExecutionRequest run, ToolExecutionGateway gateway) {
        Toolkit toolkit = new Toolkit();
        Set<String> names = new HashSet<>();
        for (RuntimeCapability capability : run.capabilities()) {
            if (!"tool".equalsIgnoreCase(capability.type()))
                throw new IllegalStateException("Only executable tool capabilities can enter AgentScope Toolkit");
            if (!registry.supports(capability.implementationKey(), capability.referenceId()))
                throw new IllegalStateException("Capability provider is unavailable");
            AgentTool tool = registry.create(capability, run, gateway);
            if (!names.add(tool.getName())) throw new IllegalStateException("Duplicate AgentScope tool name");
            toolkit.registerTool(tool);
        }
        if (names.size() != run.capabilities().size())
            throw new IllegalStateException("AgentScope Toolkit does not match the effective capability set");
        return toolkit;
    }
}
