package com.haizhuo.brain.runtime.agentscope.tool;

import com.haizhuo.brain.runtime.api.RuntimeCapabilityProviderCatalog;
import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeCapability;
import io.agentscope.core.tool.AgentTool;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 白名单提供者注册表；发布校验和 Toolkit 装配共用同一组实现映射。 */
@Component
public class CapabilityAdapterRegistry implements RuntimeCapabilityProviderCatalog {
    private final Map<String, RuntimeToolProvider> providers;

    public CapabilityAdapterRegistry(List<RuntimeToolProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                RuntimeToolProvider::implementationKey, Function.identity()));
    }

    @Override
    public boolean supports(String implementationKey, String capabilityCode) {
        if (implementationKey == null || capabilityCode == null) return false;
        RuntimeToolProvider provider = providers.get(implementationKey);
        return provider != null && provider.supports(capabilityCode);
    }

    public AgentTool create(RuntimeCapability capability, AgentExecutionRequest run, ToolExecutionGateway gateway) {
        RuntimeToolProvider provider = providers.get(capability.implementationKey());
        if (provider == null || !provider.supports(capability.referenceId()))
            throw new IllegalStateException("No registered provider for capability " + capability.referenceId());
        return provider.create(capability, run, gateway);
    }
}
