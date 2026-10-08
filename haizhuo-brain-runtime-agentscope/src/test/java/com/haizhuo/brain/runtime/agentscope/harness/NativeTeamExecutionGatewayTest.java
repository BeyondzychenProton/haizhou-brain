package com.haizhuo.brain.runtime.agentscope.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.agentscope.factory.AgentScopeModelFactory;
import com.haizhuo.brain.runtime.agentscope.factory.DefinitionWorkspaceMaterializer;
import com.haizhuo.brain.runtime.agentscope.team.NativeTeamExecutionGateway;
import com.haizhuo.brain.runtime.agentscope.team.NativeTeamMemberFactory;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeEmployeeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.runtime.api.model.RuntimeRunConstraints;
import com.haizhuo.brain.runtime.api.model.RuntimeToolSchema;
import com.haizhuo.brain.runtime.api.team.TeamExecutionPersistence;
import com.haizhuo.brain.runtime.api.team.TeamExecutionSnapshot;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.team.TeamTask;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;

/** Exercises the run-scoped gateway through native TeamTool, mailbox, and wakeup components. */
class NativeTeamExecutionGatewayTest {

    private final Path workspace = Path.of(System.getProperty("user.dir"), "target", "native-team-gateway-tests",
            java.util.UUID.randomUUID().toString());

    @Test
    @Timeout(35)
    void nativeLeadWakesWorkerAndCompletesOneParentRun() throws Exception {
        ScriptedModel lead = new LeadModel();
        ScriptedModel worker = new WorkerModel();
        AgentScopeModelFactory models = new AgentScopeModelFactory(new AgentScopeRuntimeProperties(
                "openai", "test-model", "not-used", "http://localhost", true, workspace.toString())) {
            @Override public ChatModelBase create(RuntimeDefinitionSnapshot definition) {
                return definition.definitionVersionId() == 201 ? lead : worker;
            }
        };
        NoopInbox inbox = new NoopInbox();
        InMemoryAgentStateStore agentStates = new InMemoryAgentStateStore();
        NativeTeamMemberFactory members = new NativeTeamMemberFactory(models, agentStates,
                new DefinitionWorkspaceMaterializer(workspace.resolve("definitions")), inbox);
        TestPersistence persistence = new TestPersistence();
        InMemoryStore baseStore = new InMemoryStore();
        NativeTeamExecutionGateway gateway = new NativeTeamExecutionGateway(baseStore, persistence,
                members, inbox);

        RuntimeDefinitionSnapshot definition = ownerDefinition(workerDefinition());
        RuntimeContext rootContext = RuntimeContext.builder().userId("55").sessionId("business-session")
                .put(HarnessCallContext.class, new HarnessCallContext(new RunId("parent-run"), "attempt-1", 3,
                        "business-workspace", new RuntimeRunConstraints("view", Set.of(), "caps"), null,
                        "coordinator"))
                .build();

        ExecutorService caller = Executors.newSingleThreadExecutor();
        String result;
        try {
            var execution = caller.submit(() -> gateway.execute(definition, "Inspect and summarize the source", rootContext));
            try {
                result = execution.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                execution.cancel(true);
                throw new AssertionError("Team did not finish: leadCalls=" + lead.calls.get()
                        + ", workerCalls=" + worker.calls.get() + ", events=" + persistence.memberEvents
                        + ", leadTools=" + lead.toolResults + ", workerTools=" + worker.toolResults
                        + ", baseStoreItems=" + baseStore.search(List.of(), 100, 0).stream()
                        .map(item -> item.key() + "=" + item.value()).toList(), timeout);
            }
        } finally {
            caller.shutdownNow();
            caller.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertNotNull(result);
        assertTrue(result.contains("worker-result"), result);
        assertEquals(1, persistence.started.size());
        assertTrue(persistence.completed);
        assertFalse(persistence.recoveryRequired);
        assertTrue(persistence.taskReservations >= 1);
        assertTrue(persistence.messageReservations >= 2);
        assertTrue(persistence.memberEvents.stream().anyMatch(event -> event.endsWith(":worker-a:STARTED")));
        assertTrue(persistence.memberEvents.stream().anyMatch(event -> event.endsWith(":lead:ENDED")));
        assertTrue(lead.toolResults.stream().anyMatch(value -> value.contains("worker-result")));
        assertTrue(worker.toolResults.stream().anyMatch(value -> value.contains("task-1")));
    }

    private RuntimeDefinitionSnapshot ownerDefinition(RuntimeDefinitionSnapshot worker) {
        String instructions = "只读 Team 协调者";
        String manifest = manifest("TEAM_AUTONOMOUS_READONLY");
        String contentHash = CanonicalJson.sha256(Map.of("instructions", instructions, "manifestJson", manifest,
                "fileManifest", List.of()));
        var configuration = new RuntimeEmployeeConfiguration(2, RuntimeProfile.TEAM_AUTONOMOUS_READONLY,
                new RuntimeEmployeeConfiguration.RuntimePolicy(4, 1, 2, 60, false),
                new RuntimeEmployeeConfiguration.TeamConfiguration("worker-a", List.of("worker-a"), List.of()),
                List.of(new RuntimeEmployeeConfiguration.FixedMember("worker-a", 41, 301, 4, worker)));
        return new RuntimeDefinitionSnapshot(201, "lead-agent", instructions, "openai", "test-model", 4,
                "team-owner-bundle", "team-owner-workspace", contentHash, manifest, List.of(), configuration,
                List.of());
    }

    private RuntimeDefinitionSnapshot workerDefinition() {
        String instructions = "只读研究成员";
        String manifest = manifest("SINGLE_SKILLED");
        String contentHash = CanonicalJson.sha256(Map.of("instructions", instructions, "manifestJson", manifest,
                "fileManifest", List.of()));
        return new RuntimeDefinitionSnapshot(301, "worker-agent", instructions, "openai", "test-model", 4,
                "team-worker-bundle", "team-worker-workspace", contentHash, manifest, List.<RuntimeToolSchema>of(),
                new RuntimeEmployeeConfiguration(2, RuntimeProfile.SINGLE_SKILLED,
                        new RuntimeEmployeeConfiguration.RuntimePolicy(4, 0, 0, 30, false), null, List.of()),
                List.of());
    }

    private static String manifest(String profile) {
        return CanonicalJson.write(Map.of("schemaVersion", 1, "agents", "AGENTS.md", "runtimeProfile", profile,
                "skills", List.of(), "subagents", List.of(), "knowledge", List.of(), "files", List.of()));
    }

    private static io.agentscope.core.message.ToolUseBlock team(String id, Map<String, Object> arguments) {
        return new io.agentscope.core.message.ToolUseBlock(id, "team", arguments,
                new ObjectMapper().valueToTree(arguments).toString(), null);
    }

    private abstract static class ScriptedModel extends ChatModelBase {
        final AtomicInteger calls = new AtomicInteger();
        private final List<String> toolResults = new CopyOnWriteArrayList<>();

        @Override protected final Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools,
                                                               GenerateOptions options) {
            messages.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(block -> block.getOutput().stream()).filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).map(TextBlock::getText).forEach(toolResults::add);
            return script(calls.getAndIncrement());
        }

        abstract Flux<ChatResponse> script(int call);

        ChatResponse toolResponse(String id, Map<String, Object> arguments) {
            return ChatResponse.builder().id(id).content(List.of(team(id + "-use", arguments)))
                    .finishReason("tool_calls").build();
        }

        ChatResponse textResponse(String id, String text) {
            return ChatResponse.builder().id(id).content(List.of(TextBlock.builder().text(text).build()))
                    .finishReason("stop").build();
        }
    }

    private static final class LeadModel extends ScriptedModel {
        @Override Flux<ChatResponse> script(int call) {
            return switch (call) {
                case 0 -> Flux.just(toolResponse("lead-create", Map.of("action", "createTask", "subject", "inspect",
                        "description", "Return a concise finding", "owner", "worker-a")));
                case 1 -> Flux.just(toolResponse("lead-message", Map.of("action", "sendMessage", "to_member", "worker-a",
                        "content", "Please inspect the assigned task")));
                case 2 -> Flux.just(textResponse("lead-wait", "Work assigned"));
                case 3 -> Flux.just(toolResponse("lead-read", Map.of("action", "listMessages", "limit", 20)));
                case 4 -> Flux.just(toolResponse("lead-complete", Map.of("action", "completeTeam")));
                default -> Flux.just(textResponse("lead-final", "Team completed"));
            };
        }

        @Override public String getModelName() { return "deterministic-team-lead"; }
    }

    private static final class WorkerModel extends ScriptedModel {
        @Override Flux<ChatResponse> script(int call) {
            return switch (call) {
                case 0 -> Flux.just(toolResponse("worker-claim", Map.of("action", "claimTask", "task_id", "task-1")));
                case 1 -> Flux.just(toolResponse("worker-complete", Map.of("action", "completeTask", "task_id", "task-1",
                        "result", "worker-result")));
                case 2 -> Flux.just(toolResponse("worker-message", Map.of("action", "sendMessage", "to_member", "lead",
                        "content", "worker-result")));
                default -> Flux.just(textResponse("worker-final", "Worker complete"));
            };
        }

        @Override public String getModelName() { return "deterministic-team-worker"; }
    }

    private static final class NoopInbox implements RunControlInbox {
        @Override public List<RunGuidanceMessage> consumeGuidance(RunId runId) { return List.of(); }
        @Override public boolean isCancellationRequested(RunId runId) { return false; }
    }

    private static final class TestPersistence implements TeamExecutionPersistence {
        private final List<TeamExecutionSnapshot> started = new ArrayList<>();
        private final List<String> memberEvents = new CopyOnWriteArrayList<>();
        private int taskReservations;
        private int messageReservations;
        private boolean completed;
        private boolean recoveryRequired;

        @Override public synchronized void start(TeamExecutionSnapshot execution) { started.add(execution); }

        @Override public synchronized void reserveAction(TeamExecutionSnapshot execution, Action action,
                                                         int count, int limit) {
            if (action == Action.TASK_CREATED) {
                taskReservations += count;
                if (taskReservations > limit) throw new IllegalStateException("task budget exceeded");
            } else {
                messageReservations += count;
                if (messageReservations > limit) throw new IllegalStateException("message budget exceeded");
            }
        }

        @Override public boolean recordMemberEvent(TeamExecutionSnapshot execution, String roleId, MemberEvent event,
                                                   long ordinal, Instant occurredAt) {
            memberEvents.add(execution.teamExecutionId() + ":" + roleId + ":" + event);
            return true;
        }

        @Override public synchronized boolean complete(TeamExecutionSnapshot execution, String resultSha256) {
            completed = resultSha256.matches("[a-f0-9]{64}");
            return completed;
        }

        @Override public synchronized boolean requireRecovery(TeamExecutionSnapshot execution, String reasonCode) {
            recoveryRequired = true;
            return true;
        }
    }
}
