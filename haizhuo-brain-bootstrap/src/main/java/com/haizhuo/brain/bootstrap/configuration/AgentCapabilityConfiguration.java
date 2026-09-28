package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionPublisher;
import com.haizhuo.brain.platform.tool.CapabilityExecutorRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 在应用边界装配领域服务，保持 platform 模块不依赖 Spring 容器。 */
@Configuration
@Profile("!test")
public class AgentCapabilityConfiguration {

    @Bean
    AgentDefinitionManagementService agentDefinitionManagementService(AgentDefinitionRepository definitions,
                                                                      CapabilityExecutorRegistry executors,
                                                                      HarnessDefinitionPublisher publisher) {
        return new AgentDefinitionManagementService(definitions, executors, publisher);
    }
}
