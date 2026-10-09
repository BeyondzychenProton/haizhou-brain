package com.haizhuo.brain.bootstrap.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.api.admin.OperationsOverviewProvider.OverviewResponse;
import com.haizhuo.brain.observability.LangfuseProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeOperationsOverviewProviderTest {
    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");

    @Test
    void separatesConfiguredWorkersFromLoadedBeansAndNeverInfersRemoteHealth() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("haizhuo.brain.instance-label", "prod-a")
                .withProperty("haizhuo.brain.run-worker.enabled", "true")
                .withProperty("haizhuo.brain.run-worker.lease-ttl-seconds", "120")
                .withProperty("haizhuo.brain.run-worker.reclaim-every-ticks", "10")
                .withProperty("haizhuo.brain.channel-worker.enabled", "false")
                .withProperty("haizhuo.brain.session-render-v3.enabled", "true");
        LangfuseProperties enabled = properties(true, true);

        OverviewResponse response = new RuntimeOperationsOverviewProvider(environment, false,
                false, false, true, enabled, Clock.fixed(NOW, ZoneOffset.UTC)).overview();

        assertEquals(NOW, response.observedAt());
        assertEquals("prod-a", response.instanceLabel());
        assertFalse(response.runtime().loaded());
        assertEquals("2.0.3", response.runtime().agentScopeVersion());
        assertTrue(response.runtime().profileGates().get("run-worker"));
        assertTrue(response.runtime().profileGates().get("session-render-v3"));
        assertTrue(response.workers().get(0).configuredEnabled());
        assertFalse(response.workers().get(0).loaded());
        assertEquals(120L, response.workers().get(0).leaseTtlSeconds());
        assertFalse(response.workers().get(1).configuredEnabled());
        assertFalse(response.workers().get(1).loaded());
        assertTrue(response.observability().configuredEnabled());
        assertTrue(response.observability().loaded());
        assertEquals("UNKNOWN", response.observability().probeStatus());
        assertNull(response.observability().lastProbeAt());
        assertNull(response.observability().scoreQueueDepth());
        assertTrue(response.links().isEmpty());
    }

    @Test
    void returnsOnlyHttpsLinksWithoutCredentialsOrQueryParameters() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("haizhuo.brain.operations.links.logs", "https://logs.example/tenant")
                .withProperty("haizhuo.brain.operations.links.metrics", "https://user:pass@metrics.example")
                .withProperty("haizhuo.brain.operations.links.traces", "http://traces.example?token=hidden");

        OverviewResponse response = new RuntimeOperationsOverviewProvider(environment, false,
                false, false, false, properties(false, false),
                Clock.fixed(NOW, ZoneOffset.UTC)).overview();

        assertEquals(1, response.links().size());
        assertEquals("https://logs.example/tenant", response.links().get(0).url());
    }

    @Test
    void hidesUnsafeInstanceLabelsAndDistinguishesDisabledObservability() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("haizhuo.brain.instance-label", "server.local/private");
        LangfuseProperties disabled = properties(false, false);

        OverviewResponse response = new RuntimeOperationsOverviewProvider(environment, false,
                false, false, false, disabled, Clock.fixed(NOW, ZoneOffset.UTC)).overview();

        assertNull(response.instanceLabel());
        assertEquals("NOT_CONFIGURED", response.observability().probeStatus());
        assertFalse(response.observability().configuredEnabled());
        assertFalse(response.observability().captureContentEnabled());
    }

    private static LangfuseProperties properties(boolean enabled, boolean captureContent) {
        return new LangfuseProperties(enabled, "https://observe.example", null,
                "public-placeholder", "secret-placeholder", "test", "haizhuo-test",
                captureContent, 2000, 16, 250, "4");
    }
}
