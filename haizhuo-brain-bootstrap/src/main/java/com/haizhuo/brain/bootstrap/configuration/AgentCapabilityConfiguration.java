package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.CapabilityAssetManagementService;
import com.haizhuo.brain.platform.employee.CapabilityAssetRepository;
import com.haizhuo.brain.platform.employee.EmployeeAdminCommandExecutor;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationService;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeProfileSettings;
import com.haizhuo.brain.platform.employee.RuntimeProfileAdmissionPolicy;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionPublisher;
import com.haizhuo.brain.platform.tool.CapabilityExecutorRegistry;
import org.springframework.beans.factory.ObjectProvider;
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
                                                                      HarnessDefinitionPublisher publisher,
                                                                      ObjectProvider<CapabilityAssetRepository> assets,
                                                                      RuntimeProfileAdmissionPolicy profileAdmission) {
        return new AgentDefinitionManagementService(definitions, executors, publisher,
                assets.getIfAvailable(), profileAdmission);
    }

    @Bean
    EmployeeAdministrationService employeeAdministrationService(EmployeeAdministrationStore store,
                                                                 EmployeeAdminCommandExecutor commands,
                                                                 AgentDefinitionManagementService definitions,
                                                                 EmployeeRuntimeProfileSettings profileSettings,
                                                                 RuntimeProfileAdmissionPolicy profileAdmission) {
        return new EmployeeAdministrationService(store, commands, definitions, profileSettings, profileAdmission);
    }

    @Bean
    CapabilityAssetManagementService capabilityAssetManagementService(CapabilityAssetRepository assets,
                                                                       java.time.Clock clock) {
        return new CapabilityAssetManagementService(assets, clock);
    }
}
