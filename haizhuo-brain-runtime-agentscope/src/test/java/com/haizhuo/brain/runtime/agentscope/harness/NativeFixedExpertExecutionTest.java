package com.haizhuo.brain.runtime.agentscope.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.agentscope.context.RuntimeContextFactory;
import com.haizhuo.brain.runtime.agentscope.event.AgentScopeEventTranslator;
import com.haizhuo.brain.runtime.agentscope.factory.AgentScopeModelFactory;
import com.haizhuo.brain.runtime.agentscope.factory.DefinitionWorkspaceMaterializer;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessAgentFactory;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessRuntimeTemplate;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessTemplateKey;
import com.haizhuo.brain.runtime.agentscope.middleware.ObservabilityMiddleware;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;
import com.haizhuo.brain.runtime.api.DelegationBudgetProvider;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeEmployeeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.runtime.api.model.RuntimeRunConstraints;
import com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding;
import com.haizhuo.brain.runtime.api.model.RuntimeToolSchema;
import com.haizhuo.brain.runtime.api.model.RuntimeWorkspaceFile;
import com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.UUID;
import reactor.core.publisher.Flux;

/** Proves fixed role delegation uses AgentScope's parent-aware native factory seam. */
class NativeFixedExpertExecutionTest {
    private final Path work = Path.of(System.getProperty("user.dir"), "target", "native-fixed-experts-tests",
            UUID.randomUUID().toString());

    @Test
    @Timeout(30)
    void nativeSpawnUsesOnlyAuthorizedFrozenExpertAndPropagatesParentScope() {
        ScriptedRootModel rootModel = new ScriptedRootModel();
        ScriptedExpertModel expertModel = new ScriptedExpertModel();
        AgentStateStore states = new InMemoryAgentStateStore();
        BaseStore baseStore = new MemoryBaseStore();
        DistributedStore distributed = DistributedStore.builder().agentStateStore(states).baseStore(baseStore).build();
        RemoteFilesystemSpec remote = new RemoteFilesystemSpec(baseStore).isolationScope(IsolationScope.SESSION);
        HarnessAgentFactory factory = new HarnessAgentFactory(
                new AgentScopeModelFactory(new AgentScopeRuntimeProperties(
                        "openai", "test-model", "not-used", "http://localhost", true, work.toString())),
                states, distributed, remote, new DefinitionWorkspaceMaterializer(work), new NoopInbox(),
                new ObservabilityMiddleware(), false) {
            @Override protected ChatModelBase createModel(RuntimeDefinitionSnapshot definition) {
                return "协调者".equals(definition.employeeName()) ? rootModel : expertModel;
            }
        };

        RuntimeDefinitionSnapshot definition = rootDefinition(expertDefinition());
        RuntimeDefinitionSnapshot autonomous = autonomousDefinition(expertDefinition());
        assertThrows(IllegalStateException.class,
                () -> factory.build(autonomous, HarnessTemplateKey.from(autonomous)));
        HarnessRuntimeTemplate template = factory.build(definition, HarnessTemplateKey.from(definition));
        try {
            AgentExecutionRequest request = new AgentExecutionRequest(new TenantId(1), new UserId(55),
                    new SessionId("parent-session"), new RunId("run-native-probe"), new TraceId("trace-probe"),
                    "attempt-native-probe", 17L, definition,
                    new RuntimeRunConstraints("tools", java.util.Set.of(), "capabilities"),
                    RuntimeSessionBinding.direct("parent-session", false),
                    new UserPromptExecutionInput("请研究这个问题"));
            DelegationBudgetProvider reservations = (runId, attemptId, fenceToken, roleId, maxInvocations, maxParallel) -> {
                String invocationId = UUID.randomUUID().toString();
                return () -> invocationId;
            };
            DelegationAcceptanceProvider acceptance = new DelegationAcceptanceProvider() {
                @Override public List<AcceptedDelegation> acceptBatch(RunId runId, String attemptId, long fenceToken,
                                                                       int maxInvocations, int maxParallel,
                                                                       List<DelegationCall> calls) {
                    return calls.stream().map(call -> {
                        String workItemRef = "wi-" + UUID.randomUUID();
                        var reservation = reservations.reserve(runId, attemptId, fenceToken, call.roleId(),
                                maxInvocations, maxParallel);
                        return new AcceptedDelegation(reservation, call.roleId(), workItemRef, 1,
                                call.payload(), workItemRef, call.sourceKind(), call.employeeId(),
                                call.definitionVersionId(), call.definitionHash());
                    }).toList();
                }

                @Override public Optional<WorkItemResult> readResult(RunId runId, String attemptId, long fenceToken,
                                                                      String workItemRef, int assignmentRevision) {
                    return Optional.empty();
                }

                @Override public boolean reviewResult(RunId runId, String attemptId, long fenceToken,
                                                        String workItemRef, int assignmentRevision, String resultId,
                                                        String bodySha256, String contractVersion,
                                                        ReviewDecision decision, String reason) {
                    return false;
                }
            };
            var context = new RuntimeContextFactory(reservations, acceptance).create(request);
            List<io.agentscope.core.event.AgentEvent> events = template.agent()
                    .streamEvents("请研究这个问题", context).collectList().block(Duration.ofSeconds(20));

            assertNotNull(events);
            assertEquals(1, expertModel.calls.get(), () -> rootModel.toolResults.toString());
            assertTrue(rootModel.toolResults.stream().anyMatch(value -> value.contains("research-result")));
            assertTrue(expertModel.toolNames.isEmpty(), () -> "fixed expert received tools: " + expertModel.toolNames);
            assertEquals(2, rootModel.calls.get());
        } finally {
            template.close();
        }
    }

    @Test
    @Timeout(30)
    void nativeAgentGenerateRegistersAndExecutesDynamicExpertInTheSameRun() {
        ScriptedRootModel rootModel = new ScriptedRootModel(true);
        ScriptedExpertModel expertModel = new ScriptedExpertModel();
        AgentStateStore states = new InMemoryAgentStateStore();
        BaseStore baseStore = new MemoryBaseStore();
        DistributedStore distributed = DistributedStore.builder().agentStateStore(states).baseStore(baseStore).build();
        RemoteFilesystemSpec remote = new RemoteFilesystemSpec(baseStore).isolationScope(IsolationScope.SESSION);
        HarnessAgentFactory factory = new HarnessAgentFactory(
                new AgentScopeModelFactory(new AgentScopeRuntimeProperties(
                        "openai", "test-model", "not-used", "http://localhost", true, work.toString())),
                states, distributed, remote, new DefinitionWorkspaceMaterializer(work), new NoopInbox(),
                new ObservabilityMiddleware(), false) {
            @Override protected ChatModelBase createModel(RuntimeDefinitionSnapshot definition) {
                return "协调者".equals(definition.employeeName()) ? rootModel : expertModel;
            }
        };
        RuntimeDefinitionSnapshot definition = dynamicRootDefinition();
        HarnessRuntimeTemplate template = factory.build(definition, HarnessTemplateKey.from(definition));
        try {
            AgentExecutionRequest request = new AgentExecutionRequest(new TenantId(1), new UserId(55),
                    new SessionId("dynamic-parent-session"), new RunId("run-dynamic-probe"),
                    new TraceId("trace-dynamic-probe"), "attempt-dynamic-probe", 18L, definition,
                    new RuntimeRunConstraints("tools", java.util.Set.of(), "capabilities"),
                    RuntimeSessionBinding.direct("dynamic-parent-session", false),
                    new UserPromptExecutionInput("请创建并运行只读专家"));
            DelegationBudgetProvider reservations = (runId, attemptId, fenceToken, roleId, maxInvocations, maxParallel) -> {
                String invocationId = UUID.randomUUID().toString();
                return () -> invocationId;
            };
            DelegationAcceptanceProvider acceptance = new DelegationAcceptanceProvider() {
                @Override public List<AcceptedDelegation> acceptBatch(RunId runId, String attemptId, long fenceToken,
                                                                       int maxInvocations, int maxParallel,
                                                                       List<DelegationCall> calls) {
                    return calls.stream().map(call -> {
                        String workItemRef = "wi-" + UUID.randomUUID();
                        var reservation = reservations.reserve(runId, attemptId, fenceToken, call.roleId(),
                                maxInvocations, maxParallel);
                        return new AcceptedDelegation(reservation, call.roleId(), workItemRef, 1,
                                call.payload(), workItemRef, call.sourceKind(), call.employeeId(),
                                call.definitionVersionId(), call.definitionHash());
                    }).toList();
                }

                @Override public Optional<WorkItemResult> readResult(RunId runId, String attemptId, long fenceToken,
                                                                      String workItemRef, int assignmentRevision) {
                    return Optional.empty();
                }

                @Override public boolean reviewResult(RunId runId, String attemptId, long fenceToken,
                                                        String workItemRef, int assignmentRevision, String resultId,
                                                        String bodySha256, String contractVersion,
                                                        ReviewDecision decision, String reason) {
                    return false;
                }
            };
            var context = new RuntimeContextFactory(reservations, acceptance).create(request);
            List<io.agentscope.core.event.AgentEvent> events = template.agent()
                    .streamEvents("请创建并运行只读专家", context).collectList().block(Duration.ofSeconds(20));

            assertNotNull(events);
            assertEquals(1, rootModel.dynamicChildCalls.get(), () -> rootModel.toolResults.toString());
            assertTrue(rootModel.dynamicChildToolNames.isEmpty(),
                    () -> "dynamic expert received tools: " + rootModel.dynamicChildToolNames);
            assertTrue(rootModel.toolResults.stream().anyMatch(value -> value.contains("research-result")));
            assertTrue(rootModel.toolResults.stream().anyMatch(value -> value.contains("Wrote subagent spec")));
            assertEquals(3, rootModel.calls.get());
        } finally {
            template.close();
        }
    }

    private RuntimeDefinitionSnapshot rootDefinition(RuntimeDefinitionSnapshot member) {
        String instructions = "你是负责协调的数字员工。";
        String manifest = manifest("TEAM_READONLY");
        String workspaceHash = CanonicalJson.sha256(Map.of("instructions", instructions,
                "manifestJson", manifest, "fileManifest", List.of()));
        RuntimeEmployeeConfiguration configuration = new RuntimeEmployeeConfiguration(2, RuntimeProfile.TEAM_READONLY,
                new RuntimeEmployeeConfiguration.RuntimePolicy(4, 2, 4, 20, false),
                new RuntimeEmployeeConfiguration.TeamConfiguration("researcher", List.of("researcher"),
                        List.of(new RuntimeEmployeeConfiguration.RoleDelegation("coordinator", "researcher"))),
                List.of(new RuntimeEmployeeConfiguration.FixedMember("researcher", 22, 202, 4, member)));
        return new RuntimeDefinitionSnapshot(101, "协调者", instructions, "openai", "test-model", 4,
                "root-bundle-hash", "defws:root-bundle-hash", workspaceHash, manifest, List.of(), configuration,
                List.<RuntimeWorkspaceFile>of());
    }

    private RuntimeDefinitionSnapshot dynamicRootDefinition() {
        RuntimeDefinitionSnapshot member = expertDefinition();
        String instructions = "你是负责协调的数字员工。";
        String manifest = manifest("TEAM_READONLY");
        String workspaceHash = CanonicalJson.sha256(Map.of("instructions", instructions,
                "manifestJson", manifest, "fileManifest", List.of()));
        String bundleHash = CanonicalJson.sha256(Map.of("instructions", instructions,
                "manifestJson", manifest, "workspaceContentHash", workspaceHash));
        RuntimeEmployeeConfiguration configuration = new RuntimeEmployeeConfiguration(2, RuntimeProfile.TEAM_READONLY,
                new RuntimeEmployeeConfiguration.RuntimePolicy(4, 2, 4, 20, false),
                new RuntimeEmployeeConfiguration.TeamConfiguration("researcher", List.of("researcher", "dyn-researcher"),
                        List.of(new RuntimeEmployeeConfiguration.RoleDelegation("coordinator", "dynamic-expert"))),
                List.of(new RuntimeEmployeeConfiguration.FixedMember("researcher", 22, 202, 4, member)));
        return new RuntimeDefinitionSnapshot(101, "协调者", instructions, "openai", "test-model", 4,
                bundleHash, "defws:" + bundleHash, workspaceHash, manifest, List.of(), configuration,
                List.<RuntimeWorkspaceFile>of());
    }

    private RuntimeDefinitionSnapshot expertDefinition() {
        String instructions = "你是只读研究专家。";
        String manifest = manifest("SINGLE_SKILLED");
        String workspaceHash = CanonicalJson.sha256(Map.of("instructions", instructions,
                "manifestJson", manifest, "fileManifest", List.of()));
        String bundleHash = CanonicalJson.sha256(Map.of("instructions", instructions,
                "manifestJson", manifest, "workspaceContentHash", workspaceHash));
        RuntimeToolSchema forbidden = new RuntimeToolSchema(1, "private.write", "private_write", "must not be visible",
                Map.of("type", "object"), false, false, "business.write");
        return new RuntimeDefinitionSnapshot(202, "研究员", instructions, "openai", "test-model", 4,
                bundleHash, "defws:" + bundleHash, workspaceHash, manifest, List.of(forbidden),
                new RuntimeEmployeeConfiguration(2, RuntimeProfile.SINGLE_SKILLED,
                        new RuntimeEmployeeConfiguration.RuntimePolicy(4, 0, 0, 20, false), null, List.of()),
                List.of());
    }

    private RuntimeDefinitionSnapshot autonomousDefinition(RuntimeDefinitionSnapshot member) {
        RuntimeDefinitionSnapshot base = rootDefinition(member);
        RuntimeEmployeeConfiguration unsupported = new RuntimeEmployeeConfiguration(2,
                RuntimeProfile.TEAM_AUTONOMOUS_READONLY, base.configuration().policy(),
                base.configuration().team(), base.configuration().members());
        return new RuntimeDefinitionSnapshot(base.definitionVersionId(), base.employeeName(), base.instructions(),
                base.modelProvider(), base.modelName(), base.maxIterations(), base.definitionBundleHash(),
                base.workspaceProjectionKey(), base.workspaceContentHash(), base.workspaceManifestJson(),
                base.toolCatalog(), unsupported, base.workspaceFiles());
    }

    private static String manifest(String profile) {
        return CanonicalJson.write(Map.of("schemaVersion", 1, "agents", "AGENTS.md", "runtimeProfile", profile,
                "skills", List.of(), "subagents", List.of(), "knowledge", List.of(), "files", List.of()));
    }

    private final class ScriptedRootModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger dynamicChildCalls = new AtomicInteger();
        private final List<String> toolResults = new CopyOnWriteArrayList<>();
        private final List<String> dynamicChildToolNames = new CopyOnWriteArrayList<>();
        private final boolean dynamicGeneration;

        private ScriptedRootModel() { this(false); }

        private ScriptedRootModel(boolean dynamicGeneration) {
            this.dynamicGeneration = dynamicGeneration;
        }

        @Override protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools,
                                                         GenerateOptions options) {
            if (dynamicGeneration && messages.stream().anyMatch(message -> message.getTextContent() != null
                    && message.getTextContent().contains("Produce a Markdown document with YAML frontmatter"))) {
                String spec = "---\ndescription: 只读研究专家\nmode: subagent\nhidden: false\nsteps: 2\ntools: []\n---\n"
                        + "你是动态生成的只读研究专家，只给出简洁结论。";
                return Flux.just(ChatResponse.builder().id("dynamic-spec")
                        .content(List.of(TextBlock.builder().text(spec).build())).finishReason("stop").build());
            }
            if (dynamicGeneration && messages.stream().anyMatch(message -> message.getTextContent() != null
                    && message.getTextContent().contains("你是动态生成的只读研究专家"))) {
                dynamicChildCalls.incrementAndGet();
                tools.stream().map(ToolSchema::getName).forEach(dynamicChildToolNames::add);
                return Flux.just(ChatResponse.builder().id("dynamic-expert-final")
                        .content(List.of(TextBlock.builder().text("research-result").build()))
                        .finishReason("stop").build());
            }
            int index = calls.getAndIncrement();
            messages.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(block -> block.getOutput().stream()).filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).map(TextBlock::getText).forEach(toolResults::add);
            if (dynamicGeneration && index == 1 && toolResults.stream()
                    .noneMatch(value -> value.contains("Wrote subagent spec")))
                throw new IllegalStateException("agent_generate did not persist a spec: " + toolResults);
            if (dynamicGeneration && index == 0) {
                Map<String, Object> arguments = Map.of("name", "dyn-researcher",
                        "description", "只读研究事实并给出简洁结论", "dry_run", false);
                var tool = new io.agentscope.core.message.ToolUseBlock("generate-1", "agent_generate", arguments,
                        new ObjectMapper().valueToTree(arguments).toString(), null);
                return Flux.just(ChatResponse.builder().id("root-generate").content(List.of(tool))
                        .finishReason("tool_calls").build());
            }
            if ((!dynamicGeneration && index == 0) || (dynamicGeneration && index == 1)) {
                String workItem = "{\"objective\":\"查找事实\",\"required\":true,"
                        + "\"deliverable\":{\"mediaType\":\"text/plain\","
                        + "\"contractVersion\":\"research-v1\",\"requiredFields\":[]},"
                        + "\"inputRefs\":[],\"dependsOn\":[]}";
                String role = dynamicGeneration ? "dyn-researcher" : "researcher";
                if (dynamicGeneration) workItem = workItem.replace("\"required\":true", "\"required\":false");
                Map<String, Object> arguments = Map.of("agent_id", role, "task", workItem,
                        "label", "reviewer", "timeout_seconds", 30);
                var tool = new io.agentscope.core.message.ToolUseBlock("spawn-1", "agent_spawn", arguments,
                        new ObjectMapper().valueToTree(arguments).toString(), null);
                return Flux.just(ChatResponse.builder().id("root-spawn").content(List.of(tool))
                        .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder().id("root-final")
                    .content(List.of(TextBlock.builder().text("交付结论").build())).finishReason("stop").build());
        }
        @Override public String getModelName() { return "fixed-expert-root"; }
    }

    private final class ScriptedExpertModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> toolNames = new CopyOnWriteArrayList<>();

        @Override protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools,
                                                         GenerateOptions options) {
            calls.incrementAndGet();
            // Tool execution itself is scoped by FixedExpertScopeMiddleware; the model only records
            // schemas here so the test can verify that no published business capability is attached.
            tools.stream().map(ToolSchema::getName).forEach(toolNames::add);
            return Flux.just(ChatResponse.builder().id("expert-final")
                    .content(List.of(TextBlock.builder().text("research-result").build())).finishReason("stop").build());
        }
        @Override public String getModelName() { return "fixed-expert-child"; }
    }

    private static final class NoopInbox implements RunControlInbox {
        @Override public List<RunGuidanceMessage> consumeGuidance(RunId runId) { return List.of(); }
        @Override public boolean isCancellationRequested(RunId runId) { return false; }
    }

    private static final class MemoryBaseStore implements BaseStore {
        private final Map<String, StoreItem> items = new ConcurrentHashMap<>();
        @Override public StoreItem get(List<String> namespace, String key) { return items.get(String.join("/", namespace) + "/" + key); }
        @Override public void put(List<String> namespace, String key, Map<String, Object> value) {
            String id = String.join("/", namespace) + "/" + key;
            StoreItem old = items.get(id);
            items.put(id, new StoreItem(key, value, old == null ? 1 : old.version() + 1));
        }
        @Override public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value,
                                             long expectedVersion) {
            String id = String.join("/", namespace) + "/" + key;
            java.util.concurrent.atomic.AtomicBoolean written = new java.util.concurrent.atomic.AtomicBoolean();
            items.compute(id, (ignored, old) -> {
                long currentVersion = old == null ? 0 : old.version();
                if (currentVersion != expectedVersion) return old;
                written.set(true);
                return new StoreItem(key, value, currentVersion + 1);
            });
            return written.get();
        }
        @Override public List<StoreItem> search(List<String> namespace, int limit, int offset) {
            String prefix = String.join("/", namespace) + "/";
            return items.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                    .skip(offset).limit(limit).map(Map.Entry::getValue).toList();
        }
        @Override public void delete(List<String> namespace, String key) { items.remove(String.join("/", namespace) + "/" + key); }
    }
}
