package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.capability.EffectiveCapabilitySetResolver;
import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.runtime.api.RuntimeCapabilityProviderCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 在应用边界装配领域服务，保持 platform 模块不依赖 Spring 容器。 */
@Configuration
@Profile("!test")
public class AgentCapabilityConfiguration {
    @Bean
    EffectiveCapabilitySetResolver effectiveCapabilitySetResolver(AgentDefinitionRepository definitions,
                                                                    RuntimeCapabilityProviderCatalog providers) {
        return new EffectiveCapabilitySetResolver(definitions, providers);
    }

    @Bean
    AgentDefinitionManagementService agentDefinitionManagementService(AgentDefinitionRepository definitions,
                                                                        RuntimeCapabilityProviderCatalog providers) {
        return new AgentDefinitionManagementService(definitions, providers);
    }
}
