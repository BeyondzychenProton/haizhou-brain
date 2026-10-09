package com.haizhuo.brain.bootstrap.configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haizhuo.brain.infrastructure.mcp.SimulatorMcpUserTokenProvider;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.mcp.McpEndpointPolicy;
import com.haizhuo.brain.platform.mcp.McpUserTokenProvider;
import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HarnessRuntimeConfigurationTest {
    private static final String SIMULATOR_SECRET = "test-only-simulator-secret-1234567890";
    private static final URI LOOPBACK_ENDPOINT = URI.create("http://127.0.0.1:8765/mcp");

    private final HarnessRuntimeConfiguration configuration = new HarnessRuntimeConfiguration();

    @Test
    void defaultsToUnavailableMcpIdentityAndBlocksLoopbackHttp() {
        MockEnvironment environment = new MockEnvironment();

        McpUserTokenProvider tokens = configuration.mcpUserTokenProvider(null, environment, Clock.systemUTC());
        McpEndpointPolicy endpoints = configuration.mcpEndpointPolicy(environment);

        assertFalse(tokens instanceof SimulatorMcpUserTokenProvider);
        assertThrows(IllegalStateException.class, () -> tokens.tokenFor(new UserId(1), null));
        assertThrows(IllegalArgumentException.class, () -> endpoints.validate(LOOPBACK_ENDPOINT));
    }

    @Test
    void explicitSimulatorConfigurationStillFailsClosedOutsideTheIsolatedTestProfile() {
        MockEnvironment environment = simulatorEnvironment();
        environment.setActiveProfiles("test", "prod");

        McpUserTokenProvider tokens = configuration.mcpUserTokenProvider(null, environment, Clock.systemUTC());
        McpEndpointPolicy endpoints = configuration.mcpEndpointPolicy(environment);

        assertFalse(tokens instanceof SimulatorMcpUserTokenProvider);
        assertThrows(IllegalStateException.class, () -> tokens.tokenFor(new UserId(1), null));
        assertThrows(IllegalArgumentException.class, () -> endpoints.validate(LOOPBACK_ENDPOINT));
    }

    @Test
    void simulatorIdentityAndLoopbackAreAvailableOnlyInTheTestProfile() {
        MockEnvironment environment = simulatorEnvironment();
        environment.setActiveProfiles("test");

        McpUserTokenProvider tokens = configuration.mcpUserTokenProvider(null, environment, Clock.systemUTC());
        McpEndpointPolicy endpoints = configuration.mcpEndpointPolicy(environment);

        assertInstanceOf(SimulatorMcpUserTokenProvider.class, tokens);
        assertDoesNotThrow(() -> endpoints.validate(LOOPBACK_ENDPOINT));
    }

    private static MockEnvironment simulatorEnvironment() {
        return new MockEnvironment()
                .withProperty("haizhuo.brain.mcp.simulator.enabled", "true")
                .withProperty("haizhuo.brain.mcp.simulator.secret", SIMULATOR_SECRET);
    }
}
