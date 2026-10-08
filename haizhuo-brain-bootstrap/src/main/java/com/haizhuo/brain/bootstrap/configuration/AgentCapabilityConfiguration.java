package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.CapabilityAssetManagementService;
import com.haizhuo.brain.platform.employee.CapabilityAssetRepository;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionPublisher;
import com.haizhuo.brain.platform.tool.CapabilityExecutorRegistry;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

/** 在应用边界装配领域服务，保持 platform 模块不依赖 Spring 容器。 */
@Configuration
@Profile("!test")
public class AgentCapabilityConfiguration {

    @Bean
    AgentDefinitionManagementService agentDefinitionManagementService(AgentDefinitionRepository definitions,
                                                                      CapabilityExecutorRegistry executors,
                                                                      HarnessDefinitionPublisher publisher,
                                                                      ObjectProvider<CapabilityAssetRepository> assets,
                                                                      @Value("${haizhuo.brain.employee.enabled-profiles:LEGACY_STABLE}") String enabledProfileNames) {
        Set<RuntimeProfile> enabledProfiles = Arrays.stream(enabledProfileNames.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(RuntimeProfile::valueOf).collect(Collectors.toUnmodifiableSet());
        return new AgentDefinitionManagementService(definitions, executors, publisher,
                assets.getIfAvailable(), enabledProfiles);
    }

    @Bean
    CapabilityAssetManagementService capabilityAssetManagementService(CapabilityAssetRepository assets,
                                                                       java.time.Clock clock) {
        return new CapabilityAssetManagementService(assets, clock);
    }
}
