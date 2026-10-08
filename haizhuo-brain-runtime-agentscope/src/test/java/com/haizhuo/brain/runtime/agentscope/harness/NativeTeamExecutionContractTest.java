package com.haizhuo.brain.runtime.agentscope.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.runtime.agentscope.team.ScopedTeamClient;
import com.haizhuo.brain.runtime.agentscope.team.TeamActionBudget;
import io.agentscope.core.agent.RuntimeContext;
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
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamTask;
import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** Runs lead and worker HarnessAgents through the native TeamTool and TeamsMiddleware. */
class NativeTeamExecutionContractTest {

    @TempDir Path workspace;

    @Test
    @Timeout(30)
    void leadWorkerTaskAndMailboxFlowUsesNativeTeamBoardAndWakeupMiddleware() {
        String teamName = "hb-runtime-probe-g1";
        String namespace = "runtime-probe";
        LocalTeamClient nativeClient = new LocalTeamClient(new InMemoryStore());
        nativeClient
                .createTeam(
                        new TeamCreateSpec(
                                teamName,
                                namespace,
                                "verify native team flow",
                                "lead-ref",
                                "lead prompt",
                                List.of(new TeamMemberSpec("worker-a", "worker-ref", "", "byo"))))
                .block();

        List<TeamContext.MemberSnapshot> roster =
                List.of(
                        new TeamContext.MemberSnapshot("lead", "lead-ref", "Working"),
                        new TeamContext.MemberSnapshot("worker-a", "worker-ref", "Working"));
        TeamContext leadContext =
                new TeamContext(
                        teamName,
                        namespace,
                        "verify native team flow",
                        "lead",
                        true,
                        roster,
                        List.of("listTasks", "createTask", "assignTask", "sendMessage", "completeTeam"));
        TeamContext workerContext =
                new TeamContext(
                        teamName,
                        namespace,
                        "verify native team flow",
                        "worker-a",
                        false,
                        roster,
                        List.of("listTasks", "claimTask", "completeTask", "failTask", "sendMessage"));

        LeadModel leadModel = new LeadModel();
        WorkerModel workerModel = new WorkerModel();
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        HarnessAgent lead =
                agent(
                                "lead",
                                leadModel,
                                workspace.resolve("lead"),
                                new ScopedTeamClient(
                                        nativeClient,
                                        leadContext,
                                        new TestBudget(),
                                        Set.of("worker-a")),
                                leadContext,
                                stateStore,
                                "team-lead-session")
                        .build();
        HarnessAgent worker =
                agent(
                                "worker-a",
                                workerModel,
                                workspace.resolve("worker-a"),
                                new ScopedTeamClient(
                                        nativeClient,
                                        workerContext,
                                        new TestBudget(),
                                        Set.of("lead")),
                                workerContext,
                                stateStore,
                                "team-worker-session")
                        .build();

        RuntimeContext leadRuntime =
                RuntimeContext.builder().userId("probe-user").sessionId("team-lead-session").build();
        RuntimeContext workerRuntime =
                RuntimeContext.builder().userId("probe-user").sessionId("team-worker-session").build();
        try {
            assertNotNull(lead.streamEvents("start team", leadRuntime).collectList().block(Duration.ofSeconds(10)));
            assertNotNull(worker.streamEvents("handle assigned task", workerRuntime).collectList().block(Duration.ofSeconds(10)));
            assertNotNull(lead.streamEvents("check team result", leadRuntime).collectList().block(Duration.ofSeconds(10)));

            List<TeamTask> tasks = nativeClient.listTasks(namespace, teamName).block();
            assertNotNull(tasks);
            assertEquals(1, tasks.size());
            assertEquals(TeamTask.COMPLETED, tasks.get(0).state());
            assertEquals("worker-result", tasks.get(0).result());
            assertTrue(workerModel.toolResults.stream().anyMatch(result -> result.contains("task-1")));
            assertTrue(workerModel.promptSnapshots.stream().anyMatch(messages -> promptText(messages)
                    .contains("Objective:** verify native team flow")
                            && promptText(messages).contains("Allowed tool / action names for your role")),
                    () -> workerModel.promptSnapshots.stream().map(NativeTeamExecutionContractTest::promptText).toList().toString());
            assertTrue(leadModel.toolResults.stream().anyMatch(result -> result.contains("worker-result")));
            assertTrue(leadModel.promptSnapshots.stream().anyMatch(messages -> promptText(messages)
                    .contains("worker-result")),
                    () -> leadModel.promptSnapshots.stream().map(NativeTeamExecutionContractTest::promptText).toList().toString());
            assertTrue(nativeClient.listMessages(namespace, teamName, 20).block().size() >= 3);
        } finally {
            lead.close();
            worker.close();
            TeamsMiddleware.unregisterSession("team-lead-session");
            TeamsMiddleware.unregisterSession("team-worker-session");
        }
    }

    private static HarnessAgent.Builder agent(
            String name,
            ChatModelBase model,
            Path path,
            io.agentscope.harness.agent.team.TeamClient client,
            TeamContext context,
            InMemoryAgentStateStore stateStore,
            String sessionId) {
        return HarnessAgent.builder()
                .name(name)
                .description("native Team execution probe")
                .sysPrompt("Follow the deterministic Team tool test script.")
                .model(model)
                .workspace(path)
                .toolkit(new Toolkit())
                .stateStore(stateStore)
                .maxIters(8)
                .teamsMode(client, context, sessionId)
                .disableSubagents()
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableFilesystemTools()
                .disableShellTool()
                .disableWorkspaceContext()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableDynamicSubagents()
                .disableTranscript();
    }

    private static ToolUseBlock tool(String id, Map<String, Object> input) {
        return new ToolUseBlock(id, "team", input, new ObjectMapper().valueToTree(input).toString(), null);
    }

    private static String promptText(List<Msg> messages) {
        return messages.stream()
                .flatMap(message -> message.getContentBlocks(TextBlock.class).stream())
                .map(TextBlock::getText)
                .filter(text -> text != null)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private abstract static class ScriptedModel extends ChatModelBase {
        final AtomicInteger calls = new AtomicInteger();
        final List<List<Msg>> promptSnapshots = new CopyOnWriteArrayList<>();
        final List<String> toolResults = new CopyOnWriteArrayList<>();

        @Override
        protected final Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            promptSnapshots.add(List.copyOf(messages));
            messages.stream()
                    .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(block -> block.getOutput().stream())
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .forEach(toolResults::add);
            return script(calls.getAndIncrement());
        }

        abstract Flux<ChatResponse> script(int call);

        ChatResponse toolResponse(String id, Map<String, Object> input) {
            return ChatResponse.builder()
                    .id(id)
                    .content(List.of(tool(id + "-use", input)))
                    .finishReason("tool_calls")
                    .build();
        }

        ChatResponse textResponse(String id, String text) {
            return ChatResponse.builder()
                    .id(id)
                    .content(List.of(TextBlock.builder().text(text).build()))
                    .finishReason("stop")
                    .build();
        }
    }

    private static final class LeadModel extends ScriptedModel {
        @Override
        Flux<ChatResponse> script(int call) {
            return switch (call) {
                case 0 -> Flux.just(toolResponse("lead-create", Map.of(
                        "action", "createTask",
                        "subject", "inspect source",
                        "description", "Return a short finding",
                        "owner", "worker-a")));
                case 1 -> Flux.just(textResponse("lead-started", "team-started"));
                case 2 -> Flux.just(toolResponse("lead-read", Map.of("action", "listMessages", "limit", 20)));
                default -> Flux.just(textResponse("lead-complete", "team-final"));
            };
        }

        @Override
        public String getModelName() {
            return "native-team-lead";
        }
    }

    private static final class WorkerModel extends ScriptedModel {
        @Override
        Flux<ChatResponse> script(int call) {
            return switch (call) {
                case 0 -> Flux.just(toolResponse("worker-claim", Map.of(
                        "action", "claimTask", "task_id", "task-1", "owner", "worker-a")));
                case 1 -> Flux.just(toolResponse("worker-complete", Map.of(
                        "action", "completeTask", "task_id", "task-1", "result", "worker-result")));
                case 2 -> Flux.just(toolResponse("worker-message", Map.of(
                        "action", "sendMessage", "to_member", "lead", "content", "worker-result")));
                default -> Flux.just(textResponse("worker-final", "worker-final"));
            };
        }

        @Override
        public String getModelName() {
            return "native-team-worker";
        }
    }

    private static final class TestBudget implements TeamActionBudget {
        @Override
        public void reserveTask() {}

        @Override
        public void reserveMessageRecipients(int recipients) {}
    }
}
