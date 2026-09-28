package com.haizhuo.brain.bootstrap.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.infrastructure.mcp.McpCapabilityExecutor;
import com.haizhuo.brain.infrastructure.mcp.SdkMcpRemoteClient;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.mcp.McpCatalogRepository;
import com.haizhuo.brain.platform.mcp.McpConnection;
import com.haizhuo.brain.platform.mcp.McpEndpointPolicy;
import com.haizhuo.brain.platform.mcp.McpToolSource;
import com.haizhuo.brain.platform.tool.CapabilityExecutionContext;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ResolvedCredential;
import com.haizhuo.brain.platform.tool.ToolExecutionState;
import com.haizhuo.brain.runtime.agentscope.AgentScopeRuntime;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.agentscope.context.RuntimeContextFactory;
import com.haizhuo.brain.runtime.agentscope.event.AgentScopeEventTranslator;
import com.haizhuo.brain.runtime.agentscope.factory.AgentScopeModelFactory;
import com.haizhuo.brain.runtime.agentscope.factory.DefinitionWorkspaceMaterializer;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessAgentFactory;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessTemplateCache;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentToolSuspendedEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionInput;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.ExternalToolResult;
import com.haizhuo.brain.runtime.api.model.ExternalToolResultExecutionInput;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeRunConstraints;
import com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding;
import com.haizhuo.brain.runtime.api.model.RuntimeToolSchema;
import com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput;
import com.haizhuo.brain.testsupport.mcp.SimulatedMcpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** 确定性模型 + 真实 Harness + 本地 HTTP MCP 模拟服务的分层闭环。 */
class McpModelSimulationTest {
    private static final String SECRET = "model-simulation-secret-at-least-32-characters";

    @TempDir Path workspace;

    @Test
    void modelToolCallCanBeExecutedBySimulatedMcpAndResumed() throws Exception {
        try (SimulatedMcpServer server = new SimulatedMcpServer(0, SECRET, "demo-mcp", Clock.systemUTC())) {
            server.start();
            var remote = new SdkMcpRemoteClient(new ObjectMapper(), new McpEndpointPolicy(Set.of(), true));
            var connection = new McpConnection(1, "demo-mcp", "Demo",
                    "http://127.0.0.1:" + server.port() + "/mcp", 1, true);
            String token = server.issueToken("+15550000002");
            var descriptor = remote.listTools(connection, token).stream()
                    .filter(tool -> tool.name().equals("private_note_read"))
                    .findFirst().orElseThrow();
            var catalog = mock(McpCatalogRepository.class);
            when(catalog.findSource(7L)).thenReturn(Optional.of(new McpToolSource(7L, 1L, 1L,
                    descriptor.name(), descriptor.outputSchema(), true)));
            when(catalog.findConnection(1L)).thenReturn(Optional.of(connection));
            var executor = new McpCapabilityExecutor(catalog, remote, (user, ignored) -> token);
            var capability = new CapabilityCatalogEntry(7L, "mcp.demo.read",
                    CapabilityBinding.CapabilityType.MCP, "1", "读取便笺", "Read owned note",
                    "demo_note_read", "mcp-trusted-v1", "mcp.read", descriptor.inputSchema(), true, false);

            var definition = new RuntimeDefinitionSnapshot(1L, "员工小卓", "读取当前用户的便笺。",
                    "openai", "test-model", 3, "mcp-model-bundle", "mcp-model-workspace",
                    "mcp-model-content", List.of(new RuntimeToolSchema(7L, "mcp.demo.read",
                            "demo_note_read", "Read owned note", descriptor.inputSchema(), true,
                            false, "mcp.read")));
            var model = new ReadNoteModel();
            var factory = new HarnessAgentFactory(new AgentScopeModelFactory(new AgentScopeRuntimeProperties(
                    "openai", "test-model", "test-key", "http://localhost", true, workspace.toString())),
                    new InMemoryAgentStateStore(), null, new DefinitionWorkspaceMaterializer(workspace),
                    new NoopInbox()) {
                @Override protected ChatModelBase createModel(RuntimeDefinitionSnapshot ignored) {
                    return model;
                }
            };
            var runtime = new AgentScopeRuntime(new HarnessTemplateCache(factory),
                    new RuntimeContextFactory(), new AgentScopeEventTranslator());
            var runId = new RunId("mcp-model-run");
            var userId = new UserId(1002);
            var constraints = new RuntimeRunConstraints("view-hash", Set.of("demo_note_read"), "capability-hash");
            var binding = new RuntimeSessionBinding("mcp-model-session", "mcp-model-workspace",
                    "bridge-hash", null);
            var firstEvents = runtime.execute(request(runId, userId, definition, constraints, binding,
                    new UserPromptExecutionInput("读取我的便笺"))).collectList().block();
            assertTrue(firstEvents.stream().anyMatch(AgentToolSuspendedEvent.class::isInstance),
                    "Harness must suspend the schema-only MCP tool");
            var suspended = firstEvents.stream().filter(AgentToolSuspendedEvent.class::isInstance)
                    .map(AgentToolSuspendedEvent.class::cast).findFirst().orElseThrow();
            assertFalse(firstEvents.stream().anyMatch(AgentRunCompletedEvent.class::isInstance));
            var call = suspended.toolCalls().get(0);
            assertEquals("demo_note_read", call.toolName());
            assertEquals(Map.of("noteId", "note-b"), call.arguments());

            var execution = new PlatformToolExecution(UUID.randomUUID().toString(), runId,
                    call.toolUseId(), 7L, call.toolName(), new ObjectMapper().writeValueAsString(call.arguments()),
                    "i".repeat(64), ToolExecutionState.EXECUTING, UUID.randomUUID().toString(),
                    null, null, null, null, null, Instant.now(), Instant.now());
            var result = executor.execute(new CapabilityExecutionContext(runId, userId, execution,
                    capability, ResolvedCredential.none()), call.arguments());
            assertTrue(result.success());
            assertEquals("reader's private note", result.content());

            var resumedEvents = runtime.execute(request(runId, userId, definition, constraints, binding,
                    new ExternalToolResultExecutionInput(List.of(new ExternalToolResult(call.toolUseId(),
                            call.toolName(), result.success(), result.content()))))).collectList().block();
            var completed = resumedEvents.stream().filter(AgentRunCompletedEvent.class::isInstance)
                    .map(AgentRunCompletedEvent.class::cast).findFirst().orElseThrow();
            assertEquals("读取完成", completed.result());
            assertEquals(2, model.calls.get());
            assertTrue(model.toolResults.contains("reader's private note"),
                    "the resumed model turn must receive the MCP result");
        }
    }

    private static AgentExecutionRequest request(RunId runId, UserId userId,
                                                 RuntimeDefinitionSnapshot definition,
                                                 RuntimeRunConstraints constraints,
                                                 RuntimeSessionBinding binding, AgentExecutionInput input) {
        return new AgentExecutionRequest(new TenantId(1), userId, new SessionId("mcp-session"),
                runId, new TraceId("mcp-trace"), "attempt-1", 1L, definition, constraints, binding, input);
    }

    private static final class ReadNoteModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();
        private List<String> toolResults = List.of();

        @Override protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools,
                                                         GenerateOptions options) {
            if (calls.getAndIncrement() == 0) {
                // AgentScope 2.0.3 validates the raw JSON content before suspending an external tool.
                return Flux.just(ChatResponse.builder().id("read-note")
                        .content(List.of(new ToolUseBlock("tool-use-1", "demo_note_read",
                                Map.of("noteId", "note-b"), "{\"noteId\":\"note-b\"}", null)))
                        .finishReason("tool_calls").build());
            }
            toolResults = messages.stream().flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(block -> block.getOutput().stream()).filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).map(TextBlock::getText).toList();
            return Flux.just(ChatResponse.builder().id("read-complete")
                    .content(List.of(TextBlock.builder().text("读取完成").build()))
                    .finishReason("stop").build());
        }

        @Override public String getModelName() { return "deterministic-mcp-model"; }
    }

    private static final class NoopInbox implements RunControlInbox {
        @Override public List<RunGuidanceMessage> consumeGuidance(RunId runId) { return List.of(); }
        @Override public boolean isCancellationRequested(RunId runId) { return false; }
    }
}
