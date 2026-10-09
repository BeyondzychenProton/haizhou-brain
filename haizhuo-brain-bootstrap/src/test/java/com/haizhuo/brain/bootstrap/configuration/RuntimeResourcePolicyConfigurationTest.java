package com.haizhuo.brain.bootstrap.configuration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haizhuo.brain.platform.tool.DefaultToolResourcePolicy;
import com.haizhuo.brain.platform.tool.StrictToolResourcePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeResourcePolicyConfigurationTest {
    private final RuntimeResourcePolicyConfiguration configuration = new RuntimeResourcePolicyConfiguration();

    @Test
    void defaultsToStrictPolicyAndParsesExactDeclarations() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("haizhuo.brain.tool.resource-free-capabilities", "calc.sum@1, lookup.read@2");

        assertInstanceOf(StrictToolResourcePolicy.class,
                configuration.runtimeToolResourcePolicy(environment));
    }

    @Test
    void refusesLegacyModeOutsideAnExplicitTestProfile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("haizhuo.brain.tool.resource-policy-mode", "LEGACY_CAPABILITY_ONLY");

        assertThrows(IllegalStateException.class, () -> configuration.runtimeToolResourcePolicy(environment));
    }

    @Test
    void allowsLegacyModeOnlyWithTheTestProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        environment.withProperty("haizhuo.brain.tool.resource-policy-mode", "LEGACY_CAPABILITY_ONLY");

        assertInstanceOf(DefaultToolResourcePolicy.class,
                configuration.runtimeToolResourcePolicy(environment));
    }

    @Test
    void refusesLegacyModeWhenTestProfileIsMixedWithAnotherProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test", "prod");
        environment.withProperty("haizhuo.brain.tool.resource-policy-mode", "LEGACY_CAPABILITY_ONLY");

        assertThrows(IllegalStateException.class, () -> configuration.runtimeToolResourcePolicy(environment));
    }
}
