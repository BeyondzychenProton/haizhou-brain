package com.haizhuo.brain.runtime.agentscope.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** 基于真实 AgentScope 2.0.3 的探针：确定性模型、无网络调用。 */
class HarnessP0IsolationTest {

    @Test
    void sharedHarnessKeepsConcurrentUserAndSessionStateSeparate() {
        RecordingModel model = new RecordingModel();
        HarnessAgent harness = harness(model, new InMemoryAgentStateStore());
        try {
            CompletableFuture<?> alpha = CompletableFuture.runAsync(
                    () -> harness.call("alpha-private", context("user-a", "hs-a")).block());
            CompletableFuture<?> beta = CompletableFuture.runAsync(
                    () -> harness.call("beta-private", context("user-b", "hs-a")).block());
            CompletableFuture.allOf(alpha, beta).join();
            harness.call("alpha-follow-up", context("user-a", "hs-a")).block();

            assertEquals(3, model.requests.size());
            String betaRequest = model.requests.stream().map(HarnessP0IsolationTest::requestText)
                    .filter(text -> text.contains("beta-private")).findFirst().orElseThrow();
            String alphaFollowUpRequest = model.requests.stream().map(HarnessP0IsolationTest::requestText)
                    .filter(text -> text.contains("alpha-follow-up")).findFirst().orElseThrow();
            assertFalse(betaRequest.contains("alpha-private"));
            assertFalse(betaRequest.contains("alpha-follow-up"));
            assertTrue(alphaFollowUpRequest.contains("alpha-private"));
            assertFalse(alphaFollowUpRequest.contains("beta-private"));
        } finally {
            harness.close();
        }
    }

    @Test
    void versionScopedHarnessSessionKeysKeepAgentStateSeparate() {
        RecordingModel model = new RecordingModel();
        HarnessAgent harness = harness(model, new InMemoryAgentStateStore());
        try {
            harness.call("v1-only-context", context("user-a", "hs-v1")).block();
            harness.call("v2-request", context("user-a", "hs-v2")).block();

            assertFalse(requestText(model.requests.get(1)).contains("v1-only-context"));
        } finally {
            harness.close();
        }
    }

    @Test
    void runtimeNamespaceSeparatesStoreVisibility() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystemSpec filesystem = new RemoteFilesystemSpec(store);
        NamespaceFactory namespaces = context -> List.of("haizhuo", context.get("workspaceRuntimeKey"));
        assertTrue(filesystem.hasStore());
        var runtimeFilesystem = filesystem.toFilesystem(Path.of("."), "p0-agent", namespaces);

        RuntimeContext workspaceA = RuntimeContext.builder().put("workspaceRuntimeKey", "ws-a").build();
        RuntimeContext workspaceB = RuntimeContext.builder().put("workspaceRuntimeKey", "ws-b").build();
        assertNotEquals(namespaces.getNamespace(workspaceA), namespaces.getNamespace(workspaceB));
        String path = "runtime-" + java.util.UUID.randomUUID() + ".txt";
        var write = runtimeFilesystem.write(workspaceA, path, "only-a");
        assertTrue(write.isSuccess(), write.error());
        assertTrue(runtimeFilesystem.read(workspaceA, path, 0, 100).isSuccess());
        assertEquals("only-a", runtimeFilesystem.read(workspaceA, path, 0, 100).fileData().content());
        assertFalse(runtimeFilesystem.exists(workspaceB, path));
    }

    @Test
    void schemaOnlyToolIsRecognizedAsExternalAndDoesNotRunLocally() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("meeting.reserve"));

        assertTrue(toolkit.isExternalTool("meeting.reserve"));
        assertEquals(List.of("meeting.reserve"), toolkit.getToolSchemas().stream().map(ToolSchema::getName).toList());
    }

    @Test
    void callScopedToolViewMiddlewareFiltersSchemasOnOneCachedHarness() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("A"));
        toolkit.registerSchema(schema("B"));
        toolkit.registerSchema(schema("C"));
        SchemaRecordingModel model = new SchemaRecordingModel();
        HarnessAgent harness = HarnessAgent.builder().name("tool-view-p0").model(model).toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore()).middleware(new ToolViewMiddleware())
                .disableMemoryHooks().disableMemoryTools().disableFilesystemTools().disableShellTool()
                .disableWorkspaceContext().disableSubagents().disableTranscript().build();
        try {
            Object cachedAgent = harness;
            harness.call("first", RuntimeContext.builder().userId("user-a").sessionId("hs-a")
                    .put("toolView", List.of("A", "B")).build()).block();
            harness.call("second", RuntimeContext.builder().userId("user-a").sessionId("hs-b")
                    .put("toolView", List.of("A", "C")).build()).block();

            assertEquals(List.of("A", "B"), model.schemas.get(0));
            assertEquals(List.of("A", "C"), model.schemas.get(1));
            assertEquals(cachedAgent, harness);
        } finally {
            harness.close();
        }
    }

    @Test
    void modelToolCallSuspendsAndExposesItsIdentityAndArguments() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("meeting.reserve"));
        HarnessAgent harness = HarnessAgent.builder()
                .name("external-tool-p0")
                .model(new ToolCallingModel())
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableFilesystemTools()
                .disableShellTool()
                .disableWorkspaceContext()
                .disableSubagents()
                .disableTranscript()
                .build();
        try {
            List<io.agentscope.core.event.AgentEvent> events =
                    harness.streamEvents("reserve room", context("user-a", "hs-external")).collectList().block();
            RequireExternalExecutionEvent suspension = events.stream()
                    .filter(RequireExternalExecutionEvent.class::isInstance)
                    .map(RequireExternalExecutionEvent.class::cast)
                    .findFirst().orElseThrow();

            assertEquals("tool-use-1", suspension.getToolCalls().get(0).getId());
            assertEquals("meeting.reserve", suspension.getToolCalls().get(0).getName());
            assertEquals(Map.of("room", "A-101"), suspension.getToolCalls().get(0).getInput());
        } finally {
            harness.close();
        }
    }

    @Test
    void fileBackedAgentStateCanResumeExternalToolAfterAgentRebuild() throws Exception {
        Path stateDirectory = java.nio.file.Files.createTempDirectory(Path.of("target"), "harness-p0-state-");
        ToolCallingModel model = new ToolCallingModel();
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("meeting.reserve"));
        RuntimeContext context = context("user-a", "hs-restart");
        HarnessAgent first = configuredHarness(model, toolkit, new JsonFileAgentStateStore(stateDirectory));
        try {
            List<io.agentscope.core.event.AgentEvent> events =
                    first.streamEvents("reserve room", context).collectList().block();
            assertTrue(events.stream().anyMatch(RequireExternalExecutionEvent.class::isInstance));
        } finally {
            first.close();
        }

        HarnessAgent restarted = configuredHarness(model, toolkit, new JsonFileAgentStateStore(stateDirectory));
        try {
            ToolResultBlock result = ToolResultBlock.builder().id("tool-use-1").name("meeting.reserve")
                    .output(TextBlock.builder().text("room available").build()).build();
            Msg answer = restarted.call(new ToolResultMessage(result), context).block();
            assertEquals("reservation complete", answer.getTextContent());
            Msg duplicateAnswer = restarted.call(new ToolResultMessage(result), context).block();
            assertEquals("reservation complete", duplicateAnswer.getTextContent());
            long deliveredResultBlocks = model.requests.stream().flatMap(List::stream)
                    .mapToLong(message -> message.getContentBlocks(ToolResultBlock.class).stream()
                            .filter(block -> "tool-use-1".equals(block.getId())).count()).sum();
            assertEquals(3, deliveredResultBlocks,
                    "AgentScope 2.0.3 retains and re-delivers duplicate ToolResultBlocks; P1 needs a resume guard");
        } finally {
            restarted.close();
        }
    }

    private static HarnessAgent harness(RecordingModel model, InMemoryAgentStateStore stateStore) {
        return configuredHarness(model, new Toolkit(), stateStore);
    }

    private static HarnessAgent configuredHarness(ChatModelBase model, Toolkit toolkit,
                                                   io.agentscope.core.state.AgentStateStore stateStore) {
        return HarnessAgent.builder()
                .name("p0-harness")
                .description("Deterministic P0 harness")
                .sysPrompt("Use only the current request context.")
                .model(model)
                .toolkit(toolkit)
                .stateStore(stateStore)
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableFilesystemTools()
                .disableShellTool()
                .disableWorkspaceContext()
                .disableSubagents()
                .disableTranscript()
                .build();
    }

    private static RuntimeContext context(String userId, String harnessSessionKey) {
        return RuntimeContext.builder().userId(userId).sessionId(harnessSessionKey).build();
    }

    private static ToolSchema schema(String name) {
        return ToolSchema.builder().name(name).description("P0 schema " + name)
                .parameters(Map.of("type", "object", "properties", Map.of())).build();
    }

    private static String requestText(List<Msg> messages) {
        return messages.stream().map(Msg::getTextContent).reduce("", (left, right) -> left + "\n" + right);
    }

    private static final class RecordingModel extends ChatModelBase {
        private final List<List<Msg>> requests = new CopyOnWriteArrayList<>();

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            return Flux.just(ChatResponse.builder().id("p0-response")
                    .content(List.of(TextBlock.builder().text("deterministic response").build()))
                    .finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "haizhuo-p0-fake-model";
        }
    }

    private static final class ToolCallingModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<List<Msg>> requests = new CopyOnWriteArrayList<>();

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            if (calls.getAndIncrement() == 0) {
                return Flux.just(ChatResponse.builder().id("p0-tool-call")
                        .content(List.of(new ToolUseBlock("tool-use-1", "meeting.reserve", Map.of("room", "A-101"))))
                        .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder().id("p0-final")
                    .content(List.of(TextBlock.builder().text("reservation complete").build())).finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "haizhuo-p0-tool-model";
        }
    }

    private static final class SchemaRecordingModel extends ChatModelBase {
        private final List<List<String>> schemas = new CopyOnWriteArrayList<>();

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            schemas.add(tools.stream().map(ToolSchema::getName).sorted().toList());
            return Flux.just(ChatResponse.builder().id("p0-schema-response")
                    .content(List.of(TextBlock.builder().text("ok").build())).finishReason("stop").build());
        }

        @Override public String getModelName() { return "haizhuo-p0-schema-model"; }
    }

    private static final class ToolViewMiddleware implements MiddlewareBase {
        @Override
        public Flux<io.agentscope.core.event.AgentEvent> onReasoning(
                Agent agent, RuntimeContext context, ReasoningInput input,
                Function<ReasoningInput, Flux<io.agentscope.core.event.AgentEvent>> next) {
            @SuppressWarnings("unchecked")
            List<String> allowed = context.get("toolView");
            List<ToolSchema> visible = input.tools().stream().filter(tool -> allowed.contains(tool.getName())).toList();
            return next.apply(new ReasoningInput(input.messages(), visible, input.options()));
        }
    }
}
