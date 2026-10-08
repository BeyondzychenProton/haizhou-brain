package com.haizhuo.brain.runtime.agentscope.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.agentscope.event.AgentScopeEventTranslator;
import com.haizhuo.brain.runtime.api.event.AgentInternalEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentTextDeltaEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** 使用真实 2.0.3 的 spawn/send 与转发事件；模型只产生确定性文本和工具调用。 */
class NativeSubagentEventContractTest {

    @TempDir
    Path workspace;

    @Test
    @Timeout(30)
    void synchronousSpawnAndFollowupForwardChildEventsAndKeepRootSourceNull() {
        ChildModel childModel = new ChildModel();
        HarnessAgent child = builder("researcher", childModel, workspace.resolve("child"))
                .disableSubagents().build();
        ParentModel parentModel = new ParentModel();
        HarnessAgent parent = builder("coordinator", parentModel, workspace.resolve("parent"))
                .subagentFactory("researcher", ignored -> child).build();
        try {
            RuntimeContext context = RuntimeContext.builder().userId("probe-user")
                    .sessionId("probe-session")
                    .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                    .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 10).build();
            List<AgentEvent> events = parent.streamEvents("root-task", context)
                    .collectList().block(Duration.ofSeconds(20));
            assertNotNull(events);
            assertEquals(2, childModel.requests.size(), () -> parentModel.toolResults.toString());
            List<AgentResultEvent> results = events.stream().filter(AgentResultEvent.class::isInstance)
                    .map(AgentResultEvent.class::cast).toList();
            // 本地子调用走 call()：转发增量与 start/end，不额外发出 AgentResultEvent。
            assertEquals(1, results.size());
            assertEquals("root-final", results.get(0).getResult().getTextContent());
            assertEquals(null, results.get(0).getSource());
            List<TextBlockDeltaEvent> childDeltas = events.stream()
                    .filter(TextBlockDeltaEvent.class::isInstance).map(TextBlockDeltaEvent.class::cast)
                    .filter(event -> event.getSource() != null).toList();
            assertFalse(childDeltas.isEmpty());
            assertTrue(childDeltas.stream().allMatch(event ->
                    "probe-session/researcher".equals(event.getSource())));
            String childText = childDeltas.stream().map(TextBlockDeltaEvent::getDelta)
                    .reduce("", String::concat);
            assertTrue(childText.contains("child-answer-1"));
            assertTrue(childText.contains("child-answer-2"));
            assertTrue(parentModel.toolResults.stream().anyMatch(text -> text.contains("child-answer-2")));
            assertFalse(parentModel.toolResults.stream().anyMatch(text -> text.contains("status: accepted")));
            assertEquals(2, childModel.requests.size());
            String followupHistory = childModel.requests.get(1).stream().map(Msg::getTextContent)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(followupHistory.contains("child-task"));
            assertTrue(followupHistory.contains("child-followup"));
            assertTrue(followupHistory.contains("child-answer-1"));
            assertFalse(events.stream().filter(AgentStartEvent.class::isInstance)
                    .map(AgentStartEvent.class::cast)
                    .filter(event -> event.getSource() != null)
                    .anyMatch(event -> event.getSessionId() == null));
            assertTrue(events.stream().allMatch(event -> event.getId() != null
                    && !event.getId().isBlank() && event.getCreatedAt() != null));
            try (var translator = new AgentScopeEventTranslator().forExecution("probe-session", "probe-attempt", 1L)) {
                var adapted = Flux.fromIterable(events).concatMap(event ->
                        translator.translate(new RunId("probe-run"), event)).collectList().block();
                assertEquals(1, adapted.stream().filter(AgentRunCompletedEvent.class::isInstance).count());
                assertEquals("root-final", adapted.stream().filter(AgentTextDeltaEvent.class::isInstance)
                        .map(AgentTextDeltaEvent.class::cast).map(AgentTextDeltaEvent::text)
                        .reduce("", String::concat));
                assertTrue(adapted.stream().anyMatch(AgentInternalEvent.class::isInstance));
            }
        } finally {
            parent.close();
            child.close();
        }
    }

    private static HarnessAgent.Builder builder(String name, ChatModelBase model, Path workspace) {
        return HarnessAgent.builder().name(name).description("native source contract probe")
                .sysPrompt("Follow the deterministic test script.").model(model).workspace(workspace)
                .toolkit(new Toolkit()).stateStore(new InMemoryAgentStateStore()).maxIters(6)
                .disableMemoryHooks().disableMemoryTools().disableFilesystemTools().disableShellTool()
                .disableWorkspaceContext().disableDynamicSkills().disableDefaultWorkspaceSkills()
                .disableDynamicSubagents().disableTranscript();
    }

    private static final class ParentModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> toolResults = new CopyOnWriteArrayList<>();

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools,
                                              GenerateOptions options) {
            int call = calls.getAndIncrement();
            messages.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(block -> block.getOutput().stream()).filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).map(TextBlock::getText).forEach(toolResults::add);
            if (call == 0) {
                return Flux.just(ChatResponse.builder().id("spawn-response")
                        .content(List.of(streamedTool("spawn-use", "agent_spawn",
                                Map.of("agent_id", "researcher", "task", "child-task",
                                        "label", "probe-expert", "timeout_seconds", 0))))
                        .finishReason("tool_calls").build());
            }
            if (call == 1) {
                return Flux.just(ChatResponse.builder().id("send-response")
                        .content(List.of(streamedTool("send-use", "agent_send",
                                Map.of("label", "probe-expert", "message", "child-followup",
                                        "timeout_seconds", 0))))
                        .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder().id("root-response")
                    .content(List.of(TextBlock.builder().text("root-final").build()))
                    .finishReason("stop").build());
        }

        @Override public String getModelName() { return "native-contract-parent"; }

        private static ToolUseBlock streamedTool(String id, String name, Map<String, Object> arguments) {
            // streamEvents 消费 raw content 增量，不会把仅有 input Map 的块当作参数流。
            return new ToolUseBlock(id, name, arguments,
                    new ObjectMapper().valueToTree(arguments).toString(), null);
        }
    }

    private static final class ChildModel extends ChatModelBase {
        private final List<List<Msg>> requests = new CopyOnWriteArrayList<>();

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools,
                                              GenerateOptions options) {
            requests.add(List.copyOf(messages));
            return Flux.just(ChatResponse.builder().id("child-response-" + requests.size())
                    .content(List.of(TextBlock.builder().text("child-answer-" + requests.size()).build()))
                    .finishReason("stop").build());
        }

        @Override public String getModelName() { return "native-contract-child"; }
    }
}
