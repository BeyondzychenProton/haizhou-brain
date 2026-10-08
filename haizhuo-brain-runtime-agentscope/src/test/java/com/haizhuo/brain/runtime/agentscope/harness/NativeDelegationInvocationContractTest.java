package com.haizhuo.brain.runtime.agentscope.harness;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;
import com.haizhuo.brain.runtime.api.DelegationBudgetProvider;
import com.haizhuo.brain.runtime.api.DelegationPersistenceException;
import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import com.haizhuo.brain.runtime.api.model.RuntimeRunConstraints;
import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.agentscope.context.RunScopedDelegationBudget;
import com.haizhuo.brain.runtime.agentscope.middleware.DelegatedChildScopeMiddleware;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Native labels isolate instances; only the actual child call result is a full-output boundary. */
class NativeDelegationInvocationContractTest {
    @TempDir Path workspace;

    @Test
    @Timeout(30)
    void sameRoleInstancesAndFollowupExposeIndependentCallContextsAndFullResults() {
        var children = new CopyOnWriteArrayList<HarnessAgent>();
        var invocations = new CopyOnWriteArrayList<Invocation>();
        var completed = new ConcurrentHashMap<String, String>();
        var observedTypes = new CopyOnWriteArrayList<String>();
        var provider = new ProbeProvider();
        var runId = new RunId("native-same-role-probe");
        var budget = new RunScopedDelegationBudget((run, attempt, fence, role, count, parallel) -> () -> role,
                provider, runId, "attempt-probe", 17, 4, 2, Map.of(), true, false);
        budget.acceptBatch(List.of(probeCall("task-a"), probeCall("task-b")));
        budget.acceptBatch(List.of(probeCall("task-a-followup")));
        var parentContext = RuntimeContext.builder().userId("probe-user").sessionId("probe-parent")
                .put(RunScopedDelegationBudget.class, budget)
                .put(HarnessCallContext.class, new HarnessCallContext(runId, "attempt-probe", 17,
                        "native-probe-workspace", new RuntimeRunConstraints("tools", Set.of(), "caps"), null))
                .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 10).build();
        HarnessAgent parent = builder("coordinator", new ParentModel(), workspace.resolve("parent"))
                .subagentFactory("general-purpose", ignored -> {
                    HarnessAgent child = builder("general-purpose", new ChildModel(),
                            workspace.resolve("child-" + children.size())).disableSubagents()
                            .middleware(new DelegatedChildScopeMiddleware("general-purpose", parentContext))
                            .middleware(new MiddlewareBase() {
                                @Override
                                public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                                               Function<AgentInput, Flux<AgentEvent>> next) {
                                    String task = input.msgs().get(0).getTextContent();
                                    invocations.add(new Invocation(task, context.getSessionId(), context.getUserId(), context));
                                    return next.apply(input).doOnNext(event -> {
                                        observedTypes.add(event.getClass().getSimpleName());
                                        if (event instanceof AgentResultEvent result)
                                            completed.put(task, result.getResult().getTextContent());
                                    });
                                }
                            }).build();
                    children.add(child);
                    return child;
                }).build();
        try {
            List<AgentEvent> events = parent.streamEvents("root-task", parentContext)
                    .collectList().block(Duration.ofSeconds(20));
            assertNotNull(events);
            assertEquals(2, children.size());
            assertEquals(3, invocations.size());
            Invocation first = invocation(invocations, "task-a");
            Invocation second = invocation(invocations, "task-b");
            Invocation followup = invocation(invocations, "task-a-followup");
            assertNotEquals(first.sessionId(), second.sessionId());
            assertEquals(first.sessionId(), followup.sessionId());
            assertNotSame(first.context(), second.context());
            assertTrue(invocations.stream().allMatch(value -> "probe-user".equals(value.userId())));
            assertEquals("probe-parent", parentContext.getSessionId());
            assertEquals(Map.of("task-a", "answer:task-a", "task-b", "answer:task-b",
                    "task-a-followup", "answer:task-a-followup"), completed, observedTypes::toString);
            budget.verifyPersistence();
            assertEquals(3, provider.completed.size());
            assertEquals(first.sessionId(), provider.completed.get("task-a").descriptor().nativeSessionId());
            assertEquals(second.sessionId(), provider.completed.get("task-b").descriptor().nativeSessionId());
            assertEquals(first.sessionId(), provider.completed.get("task-a-followup").descriptor().nativeSessionId());
            provider.completed.forEach((task, result) -> {
                assertEquals("invocation-" + task, result.invocationId());
                assertEquals("answer:" + task, result.body());
            });
            // These paths are equal, so native source alone is not an invocation identity.
            assertTrue(events.stream().filter(AgentStartEvent.class::isInstance).map(AgentStartEvent.class::cast)
                    .filter(event -> event.getSource() != null)
                    .allMatch(event -> "probe-parent/general-purpose".equals(event.getSource())));
            assertEquals(1, events.stream().filter(AgentResultEvent.class::isInstance).count());
        } finally {
            parent.close();
            children.forEach(HarnessAgent::close);
        }
    }

    @Test
    void exactAcceptedInputIsRequiredAndPersistenceFailureSurvivesNativeErrorWrapping() {
        var provider = new ProbeProvider();
        var budget = new RunScopedDelegationBudget((run, attempt, fence, role, count, parallel) -> () -> role,
                provider, new RunId("binding-probe"), "attempt-probe", 17, 4, 2, Map.of(), true, false);
        budget.acceptBatch(List.of(probeCall("task-a"), probeCall("task-b")));
        assertThrows(SecurityException.class, () -> budget.activate("general-purpose", "unaccepted text"));
        var second = budget.activate("general-purpose", "task-b");
        var first = budget.activate("general-purpose", "task-a");
        assertEquals("invocation-task-b", second.invocationId());
        assertEquals("invocation-task-a", first.invocationId());
        provider.failCompletion = true;
        var descriptor = new AgentEventDescriptor("evt", "now", "AgentResultEvent", null, "reply", null,
                null, "native-b", null, "parent", AgentExecutionRole.CHILD, "attempt-probe", 17L);
        assertThrows(DelegationPersistenceException.class, () -> budget.complete(second, "full result", descriptor));
        assertThrows(DelegationPersistenceException.class, budget::verifyPersistence);
        first.close();
        second.close();
    }

    private static DelegationAcceptanceProvider.DelegationCall probeCall(String task) {
        return new DelegationAcceptanceProvider.DelegationCall(task, DelegationAcceptanceProvider.Operation.SPAWN,
                "general-purpose", DelegationAcceptanceProvider.SourceKind.BUILTIN_GENERAL_PURPOSE,
                null, null, null, null, null, task);
    }

    private static final class ProbeProvider implements DelegationAcceptanceProvider {
        private final Map<String, CompletedInvocation> completed = new ConcurrentHashMap<>();
        private boolean failCompletion;
        @Override public List<AcceptedDelegation> acceptBatch(RunId run, String attempt, long fence, int count,
                                                              int parallel, List<DelegationCall> calls) {
            return calls.stream().map(call -> new AcceptedDelegation(() -> "invocation-" + call.toolUseId(),
                    call.roleId(), "wi-" + call.toolUseId(), 1, call.payload(), "wi-" + call.toolUseId(),
                    call.sourceKind(), null, null, null)).toList();
        }
        @Override public Optional<String> completeInvocation(RunId run, String attempt, long fence, CompletedInvocation result) {
            if (failCompletion) throw new IllegalStateException("injected result failure");
            completed.put(result.acceptedPayload(), result);
            return Optional.of("result-" + result.invocationId());
        }
        @Override public Optional<WorkItemResult> readResult(RunId run, String attempt, long fence, String ref, int revision) {
            return Optional.empty();
        }
        @Override public boolean reviewResult(RunId run, String attempt, long fence, String ref, int revision,
                                             String resultId, String hash, String contract, ReviewDecision decision, String reason) {
            return false;
        }
    }

    private static Invocation invocation(List<Invocation> invocations, String task) {
        return invocations.stream().filter(value -> task.equals(value.task())).findFirst().orElseThrow();
    }

    private static HarnessAgent.Builder builder(String name, ChatModelBase model, Path workspace) {
        return HarnessAgent.builder().name(name).description("native invocation contract")
                .sysPrompt("Follow the deterministic test script.").model(model).workspace(workspace)
                .toolkit(new Toolkit()).stateStore(new InMemoryAgentStateStore()).maxIters(4)
                .disableMemoryHooks().disableMemoryTools().disableFilesystemTools().disableShellTool()
                .disableWorkspaceContext().disableDynamicSkills().disableDefaultWorkspaceSkills()
                .disableDynamicSubagents().disableTranscript();
    }

    private record Invocation(String task, String sessionId, String userId, RuntimeContext context) { }

    private static final class ParentModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();
        @Override protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int call = calls.getAndIncrement();
            if (call == 0) return Flux.just(ChatResponse.builder().id("parallel-spawn").content(List.of(
                    tool("spawn-a", "agent_spawn", Map.of("agent_id", "general-purpose", "task", "task-a", "label", "probe-a")),
                    tool("spawn-b", "agent_spawn", Map.of("agent_id", "general-purpose", "task", "task-b", "label", "probe-b"))))
                    .finishReason("tool_calls").build());
            if (call == 1) return Flux.just(ChatResponse.builder().id("followup").content(List.of(
                    tool("send-a", "agent_send", Map.of("label", "probe-a", "message", "task-a-followup"))))
                    .finishReason("tool_calls").build());
            return Flux.just(ChatResponse.builder().id("root-final")
                    .content(List.of(TextBlock.builder().text("root-final").build())).finishReason("stop").build());
        }
        @Override public String getModelName() { return "native-parent-probe"; }
        private static ToolUseBlock tool(String id, String name, Map<String, Object> input) {
            return new ToolUseBlock(id, name, input, new ObjectMapper().valueToTree(input).toString(), null);
        }
    }

    private static final class ChildModel extends ChatModelBase {
        @Override protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String task = messages.stream().filter(message -> message.getRole() == MsgRole.USER)
                    .reduce((first, second) -> second).orElseThrow().getTextContent();
            return Mono.delay(Duration.ofMillis(100)).map(ignored -> ChatResponse.builder().id("child-" + task)
                    .content(List.of(TextBlock.builder().text("answer:" + task).build())).finishReason("stop").build()).flux();
        }
        @Override public String getModelName() { return "native-child-probe"; }
    }
}
