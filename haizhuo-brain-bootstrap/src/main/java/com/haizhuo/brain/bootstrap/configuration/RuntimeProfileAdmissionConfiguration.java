package com.haizhuo.brain.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeProfileSettings;
import com.haizhuo.brain.platform.employee.RuntimeProfileAdmissionPolicy;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class RuntimeProfileAdmissionConfiguration {
    @Bean
    EmployeeRuntimeProfileSettings employeeRuntimeProfileSettings(
            @Value("${haizhuo.brain.employee.enabled-profiles:LEGACY_STABLE}") String enabledProfileNames) {
        return EmployeeRuntimeProfileSettings.parse(enabledProfileNames);
    }

    @Bean
    DeploymentVerificationManifestReader deploymentVerificationManifestReader(
            ObjectMapper objectMapper, Clock clock, Environment environment) {
        return new DeploymentVerificationManifestReader(objectMapper, clock,
                environment.getProperty("haizhuo.brain.runtime.verification-manifest", ""),
                environment.getProperty("haizhuo.brain.runtime.deployment-commit", ""),
                environment.getProperty("haizhuo.brain.runtime.deployment-environment", ""));
    }

    @Bean
    RuntimeProfileAdmissionPolicy runtimeProfileAdmissionPolicy(EmployeeRuntimeProfileSettings settings,
                                                                 DeploymentVerificationManifestReader reader) {
        return new ManifestRuntimeProfileAdmissionPolicy(settings, reader);
    }
}
