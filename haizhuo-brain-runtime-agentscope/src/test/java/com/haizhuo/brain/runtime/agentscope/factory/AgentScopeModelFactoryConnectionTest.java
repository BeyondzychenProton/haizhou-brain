package com.haizhuo.brain.runtime.agentscope.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haizhuo.brain.runtime.agentscope.TestRequests;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeEmployeeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeModelConnectionRef;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

class AgentScopeModelFactoryConnectionTest {

    private static final RuntimeModelConnectionRef REF = new RuntimeModelConnectionRef(
            "connection-1", 3, "a".repeat(64));

    @Test
    void exactReferenceIsResolvedOnlyWhenNativeModelStreamIsSubscribed() {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        Model delegate = model("test-model", Flux.empty());
        AgentScopeModelFactory factory = new AgentScopeModelFactory(properties(), (reference, provider, modelName) -> {
            resolutions.incrementAndGet();
            assertEquals(REF, reference);
            assertEquals("openai", provider);
            assertEquals("test-model", modelName);
            return new ResolvedModelConnection(REF, provider, modelName, delegate, releases::incrementAndGet);
        });

        Model wrapped = factory.create(definition().withModelConnectionRef(REF));
        assertEquals(0, resolutions.get());
        wrapped.stream(List.of(), List.of(), null).then().block();

        assertEquals(1, resolutions.get());
        assertEquals(1, releases.get());
    }

    @Test
    void configuredGlobalKeyDoesNotBypassUnavailableExactReference() {
        AgentScopeRuntimeProperties configured = new AgentScopeRuntimeProperties(
                "openai", "fallback-model", "not-a-real-secret", "https://example.invalid",
                true, "data/definition-workspace");
        AgentScopeModelFactory factory = new AgentScopeModelFactory(configured);
        Model wrapped = factory.create(definition().withModelConnectionRef(REF));

        ModelConnectionUnavailableException error = assertThrows(ModelConnectionUnavailableException.class,
                () -> wrapped.stream(List.of(), List.of(), null).blockLast());
        assertEquals("Credential reference resolution is not configured", error.getMessage());
    }

    @Test
    void unboundSnapshotDoesNotUseConfiguredGlobalKey() {
        AgentScopeRuntimeProperties configured = new AgentScopeRuntimeProperties(
                "openai", "fallback-model", "not-a-real-secret", "https://example.invalid",
                true, "data/definition-workspace");
        AgentScopeModelFactory factory = new AgentScopeModelFactory(configured);

        assertThrows(ModelConnectionUnavailableException.class,
                () -> factory.create(definition()));
    }

    @Test
    void explicitLegacyDeploymentPinStillRequiresAResolverAndNeverReadsGlobalPropertiesDirectly() {
        AgentScopeRuntimeProperties configured = new AgentScopeRuntimeProperties(
                "openai", "fallback-model", "not-a-real-secret", "https://example.invalid",
                true, "data/definition-workspace");
        RuntimeModelConnectionRef legacyPin = RuntimeModelConnectionRef.legacyDeploymentPin(
                "legacy-prod-profile", 1, "f".repeat(64));
        AgentScopeModelFactory factory = new AgentScopeModelFactory(configured);

        assertThrows(ModelConnectionUnavailableException.class,
                () -> factory.create(definition().withModelConnectionRef(legacyPin))
                        .stream(List.of(), List.of(), null).blockLast());
    }

    @Test
    void mismatchedResolvedRevisionIsReleasedAndRejectedBeforeProviderCall() {
        RuntimeModelConnectionRef wrongRef = new RuntimeModelConnectionRef(
                "connection-1", 2, "b".repeat(64));
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        Model delegate = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                providerCalls.incrementAndGet();
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return "test-model";
            }
        };
        AgentScopeModelFactory factory = new AgentScopeModelFactory(properties(), (reference, provider, modelName) ->
                new ResolvedModelConnection(wrongRef, provider, modelName, delegate, releases::incrementAndGet));
        Model wrapped = factory.create(definition().withModelConnectionRef(REF));

        assertThrows(ModelConnectionUnavailableException.class,
                () -> wrapped.stream(List.of(), List.of(), null).blockLast());
        assertEquals(0, providerCalls.get());
        assertEquals(1, releases.get());
    }

    @Test
    void referenceLeaseIsReleasedAfterErrorAndCancellation() {
        AtomicInteger errorReleases = new AtomicInteger();
        AgentScopeModelFactory errorFactory = new AgentScopeModelFactory(properties(),
                (reference, provider, modelName) -> new ResolvedModelConnection(REF, provider, modelName,
                        model("test-model", Flux.error(new IllegalStateException("provider failed"))),
                        errorReleases::incrementAndGet));
        assertThrows(IllegalStateException.class,
                () -> errorFactory.create(definition().withModelConnectionRef(REF))
                        .stream(List.of(), List.of(), null).blockLast());
        assertEquals(1, errorReleases.get());

        AtomicInteger cancelReleases = new AtomicInteger();
        AgentScopeModelFactory cancelFactory = new AgentScopeModelFactory(properties(),
                (reference, provider, modelName) -> new ResolvedModelConnection(REF, provider, modelName,
                        model("test-model", Flux.never()), cancelReleases::incrementAndGet));
        Disposable subscription = cancelFactory.create(definition().withModelConnectionRef(REF))
                .stream(List.of(), List.of(), null).subscribe();
        subscription.dispose();
        assertEquals(1, cancelReleases.get());
    }

    @Test
    void connectionRevisionAndHashSeparateHarnessTemplateCacheKeys() {
        RuntimeDefinitionSnapshot base = definition();
        RuntimeModelConnectionRef newerRevision = new RuntimeModelConnectionRef(
                "connection-1", 4, "c".repeat(64));
        RuntimeModelConnectionRef differentConnection = new RuntimeModelConnectionRef(
                "connection-2", 3, "a".repeat(64));

        assertNotEquals(HarnessTemplateKey.from(base.withModelConnectionRef(REF)),
                HarnessTemplateKey.from(base.withModelConnectionRef(newerRevision)));
        assertNotEquals(HarnessTemplateKey.from(base.withModelConnectionRef(REF)),
                HarnessTemplateKey.from(base.withModelConnectionRef(differentConnection)));
    }

    @Test
    void fixedMemberConnectionRevisionAlsoSeparatesHarnessTemplateCacheKeys() {
        RuntimeDefinitionSnapshot base = definition();
        RuntimeDefinitionSnapshot memberV1 = definition().withModelConnectionRef(REF);
        RuntimeDefinitionSnapshot memberV2 = definition().withModelConnectionRef(new RuntimeModelConnectionRef(
                "connection-1", 4, "c".repeat(64)));
        RuntimeDefinitionSnapshot rootV1 = withMember(base, memberV1);
        RuntimeDefinitionSnapshot rootV2 = withMember(base, memberV2);

        assertNotEquals(HarnessTemplateKey.from(rootV1), HarnessTemplateKey.from(rootV2));
    }

    @Test
    void resolvedModelLeaseStringRepresentationDoesNotExposeDelegateDetails() {
        Model secretBearingDelegate = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return "test-model";
            }

            @Override
            public String toString() {
                return "model(apiKey=do-not-log)";
            }
        };
        ResolvedModelConnection resolved = new ResolvedModelConnection(
                REF, "openai", "test-model", secretBearingDelegate, () -> {});

        assertFalse(resolved.toString().contains("do-not-log"));
        assertFalse(resolved.toString().contains("apiKey"));
    }

    private static AgentScopeRuntimeProperties properties() {
        return new AgentScopeRuntimeProperties(null, null, null, null, true, null);
    }

    private static RuntimeDefinitionSnapshot definition() {
        return TestRequests.definition("connection-bound-definition", List.of());
    }

    private static RuntimeDefinitionSnapshot withMember(RuntimeDefinitionSnapshot root,
                                                        RuntimeDefinitionSnapshot memberDefinition) {
        RuntimeEmployeeConfiguration.FixedMember member = new RuntimeEmployeeConfiguration.FixedMember(
                "researcher", 5, memberDefinition.definitionVersionId(), 2, memberDefinition);
        RuntimeEmployeeConfiguration.TeamConfiguration team = new RuntimeEmployeeConfiguration.TeamConfiguration(
                "researcher", List.of("researcher"), List.of());
        RuntimeEmployeeConfiguration configuration = new RuntimeEmployeeConfiguration(1,
                RuntimeProfile.TEAM_READONLY, new RuntimeEmployeeConfiguration.RuntimePolicy(3, 1, 2, 30, false),
                team, List.of(member));
        return new RuntimeDefinitionSnapshot(root.definitionVersionId(), root.employeeName(), root.instructions(),
                root.modelProvider(), root.modelName(), root.maxIterations(), root.definitionBundleHash(),
                root.workspaceProjectionKey(), root.workspaceContentHash(), root.workspaceManifestJson(),
                root.toolCatalog(), configuration, root.workspaceFiles());
    }

    private static Model model(String name, Flux<ChatResponse> responses) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return responses;
            }

            @Override
            public String getModelName() {
                return name;
            }
        };
    }
}
