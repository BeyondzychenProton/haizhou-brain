package com.haizhuo.brain.bootstrap.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.api.admin.RuntimeReadinessProvider;
import com.haizhuo.brain.api.admin.RuntimeReadinessProvider.GateState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeProfileSettings;
import com.haizhuo.brain.platform.employee.RuntimeProfileAdmissionPolicy;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class RuntimeReadinessConfigurationTest {
    @Test
    void reportsFixedClosedGatesWithoutClaimingValidation() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T03:00:00Z"), ZoneOffset.UTC);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        DeploymentVerificationManifestReader manifest = new DeploymentVerificationManifestReader(
                new ObjectMapper(), clock, "", "", "");
        RunArtifactProperties artifactProperties = new RunArtifactProperties();
        MarkdownArtifactAdmission artifactAdmission = MarkdownArtifactAdmission.evaluate(artifactProperties, manifest);
        RuntimeReadinessProvider provider = new RuntimeReadinessConfiguration()
                .runtimeReadinessProvider(new MockEnvironment(), beans.getBeanProvider(
                                com.haizhuo.brain.platform.channel.ChannelOutboundSender.class),
                        beans.getBeanProvider(com.haizhuo.brain.platform.mcp.McpUserTokenProvider.class),
                        beans.getBeanProvider(com.haizhuo.brain.platform.tool.ToolResourcePolicy.class),
                        policy(Set.of(RuntimeProfile.LEGACY_STABLE), manifest), manifest, artifactAdmission, clock);

        RuntimeReadinessProvider.ReadinessResponse response = provider.readiness();
        assertEquals(1, response.schemaVersion());
        assertEquals(null, response.nextCursor());
        assertFalse(response.hasMore());
        assertTrue(response.items().size() >= 13);
        assertTrue(response.items().stream().noneMatch(RuntimeReadinessProvider.GateStatus::enabled));
        assertTrue(response.items().stream().noneMatch(RuntimeReadinessProvider.GateStatus::verificationMatched));
        assertEquals(GateState.IMPLEMENTED_CLOSED, item(response, "channel.real-im").state());
        assertEquals("PROVIDER_NOT_SELECTED", item(response, "channel.real-im").reasonCode());
        assertNotNull(item(response, "channel.real-im").checkedAt());
        assertEquals(GateState.IMPLEMENTED_CLOSED, item(response, "profile.single-skilled").state());
        assertEquals("PROFILE_DISABLED", item(response, "profile.single-skilled").reasonCode());
        assertEquals("ARTIFACT_STORAGE_NOT_CONFIGURED",
                item(response, "artifact.markdown-export").reasonCode());
        assertEquals(GateState.IMPLEMENTED_CLOSED, item(response, "artifact.content-blocks").state());
        assertEquals(GateState.DESIGNED, item(response, "model.connection-directory").state());
    }

    @Test
    void reportsConfiguredProfileSeparatelyFromItsClosedGate() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T03:00:00Z"), ZoneOffset.UTC);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("haizhuo.brain.employee.enabled-profiles", "LEGACY_STABLE,SINGLE_SKILLED");
        DeploymentVerificationManifestReader manifest = new DeploymentVerificationManifestReader(
                new ObjectMapper(), clock, "", "", "");
        MarkdownArtifactAdmission artifactAdmission = MarkdownArtifactAdmission.evaluate(
                new RunArtifactProperties(), manifest);
        RuntimeReadinessProvider provider = new RuntimeReadinessConfiguration()
                .runtimeReadinessProvider(environment, beans.getBeanProvider(
                                com.haizhuo.brain.platform.channel.ChannelOutboundSender.class),
                        beans.getBeanProvider(com.haizhuo.brain.platform.mcp.McpUserTokenProvider.class),
                        beans.getBeanProvider(com.haizhuo.brain.platform.tool.ToolResourcePolicy.class),
                        policy(Set.of(RuntimeProfile.LEGACY_STABLE, RuntimeProfile.SINGLE_SKILLED), manifest),
                        manifest, artifactAdmission, clock);

        RuntimeReadinessProvider.GateStatus profile = item(provider.readiness(), "profile.single-skilled");
        assertTrue(profile.configured());
        assertFalse(profile.enabled());
        assertFalse(profile.verificationMatched());
        assertEquals("PROFILE_VERIFICATION_MISSING", profile.reasonCode());
        assertEquals("MANIFEST_NOT_CONFIGURED", profile.verificationReasonCode());
    }

    private static RuntimeProfileAdmissionPolicy policy(Set<RuntimeProfile> configured,
                                                        DeploymentVerificationManifestReader manifest) {
        return new ManifestRuntimeProfileAdmissionPolicy(new EmployeeRuntimeProfileSettings(configured), manifest);
    }

    private static RuntimeReadinessProvider.GateStatus item(
            RuntimeReadinessProvider.ReadinessResponse response, String gateId) {
        return response.items().stream().filter(item -> gateId.equals(item.gateId())).findFirst().orElseThrow();
    }
}
