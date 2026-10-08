package com.haizhuo.brain.runtime.agentscope.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamConflictException;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamTask;
import io.agentscope.harness.agent.tool.TeamTool;
import com.haizhuo.brain.runtime.agentscope.team.ScopedTeamClient;
import com.haizhuo.brain.runtime.agentscope.team.TeamActionBudget;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Executable probes for the AgentScope 2.0.3 native Team client and tool contract. */
class NativeTeamContractTest {

    private static final String TEAM = "hb-probe-g1";
    private static final String NAMESPACE = "tenant-probe";

    @Test
    void nativeTaskBoardUsesCasAndMailboxMessagesAreVisibleBeforeSendReturns() {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(
                        new TeamCreateSpec(
                                TEAM,
                                NAMESPACE,
                                "probe objective",
                                "lead-ref",
                                "lead prompt",
                                List.of(new TeamMemberSpec("worker-a", "worker-ref", "", "byo"))))
                .block();

        TeamTask created =
                client.createTask(NAMESPACE, TEAM, "inspect", "details", List.of(), "")
                        .block();
        TeamTask assigned =
                client.assignTask(NAMESPACE, TEAM, created.taskId(), "worker-a", created.version())
                        .block();
        TeamTask claimed =
                client.claimTask(
                                NAMESPACE,
                                TEAM,
                                assigned.taskId(),
                                "worker-a",
                                assigned.version())
                        .block();

        assertEquals(TeamTask.IN_PROGRESS, claimed.state());
        assertEquals("worker-a", claimed.owner());
        assertThrows(
                TeamConflictException.class,
                () ->
                        client.claimTask(
                                        NAMESPACE,
                                        TEAM,
                                        assigned.taskId(),
                                        "worker-b",
                                        assigned.version())
                                .block());

        client.sendMessage(NAMESPACE, TEAM, "lead", "worker-a", "assigned").block();
        var mailbox = client.listMessages(NAMESPACE, TEAM, 20).block();
        assertNotNull(mailbox);
        assertFalse(mailbox.isEmpty());
        assertTrue(mailbox.stream().anyMatch(message -> "assigned".equals(message.content())));
    }

    @Test
    void nativeToolDoesNotBindClaimIdentityAndEmptyActionListAllowsEveryAction() {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(
                        new TeamCreateSpec(
                                TEAM,
                                NAMESPACE,
                                "probe objective",
                                "lead-ref",
                                "lead prompt",
                                List.of(
                                        new TeamMemberSpec("worker-a", "worker-a-ref", "", "byo"),
                                        new TeamMemberSpec("worker-b", "worker-b-ref", "", "byo"))))
                .block();
        TeamTask task = client.createTask(NAMESPACE, TEAM, "claim", "", List.of(), "").block();

        TeamContext workerContext =
                new TeamContext(
                        TEAM,
                        NAMESPACE,
                        "probe objective",
                        "worker-a",
                        false,
                        List.of(),
                        List.of("claimTask"));
        String claimed =
                new TeamTool(client, workerContext)
                        .team(
                                "claimTask",
                                task.taskId(),
                                null,
                                null,
                                "worker-b",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null);

        assertTrue(claimed.contains("worker-b"));
        assertEquals("worker-b", client.listTasks(NAMESPACE, TEAM).block().get(0).owner());

        TeamContext emptyActions =
                new TeamContext(TEAM, NAMESPACE, "probe objective", "worker-a", false, List.of(), List.of());
        String completed =
                new TeamTool(client, emptyActions)
                        .team(
                                "completeTeam",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null);
        assertEquals("{\"ok\":true}", completed);
    }

    @Test
    void scopedClientBindsClaimIdentityAndRejectsEmptyOrOutOfScopeActions() {
        LocalTeamClient nativeClient = new LocalTeamClient(new InMemoryStore());
        nativeClient.createTeam(
                        new TeamCreateSpec(
                                TEAM,
                                NAMESPACE,
                                "probe objective",
                                "lead-ref",
                                "lead prompt",
                                List.of(
                                        new TeamMemberSpec("worker-a", "worker-a-ref", "", "byo"),
                                        new TeamMemberSpec("worker-b", "worker-b-ref", "", "byo"))))
                .block();
        TeamTask task = nativeClient.createTask(NAMESPACE, TEAM, "claim", "", List.of(), "").block();
        TeamContext workerContext =
                new TeamContext(
                        TEAM,
                        NAMESPACE,
                        "probe objective",
                        "worker-a",
                        false,
                        List.of(
                                new TeamContext.MemberSnapshot("lead", "lead-ref", "Working"),
                                new TeamContext.MemberSnapshot("worker-a", "worker-a-ref", "Working"),
                                new TeamContext.MemberSnapshot("worker-b", "worker-b-ref", "Working")),
                        List.of("listTasks", "claimTask", "completeTask", "failTask", "sendMessage"));
        TeamActionBudget budget = new CountingBudget();
        ScopedTeamClient scoped =
                new ScopedTeamClient(nativeClient, workerContext, budget, Set.of("worker-b"));

        String result =
                new TeamTool(scoped, workerContext)
                        .team(
                                "claimTask",
                                task.taskId(),
                                null,
                                null,
                                "worker-b",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null);

        assertTrue(result.contains("worker-a"));
        assertEquals("worker-a", nativeClient.listTasks(NAMESPACE, TEAM).block().get(0).owner());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ScopedTeamClient(
                                nativeClient,
                                new TeamContext(TEAM, NAMESPACE, "", "worker-a", false, List.of(), List.of()),
                                budget,
                                Set.of()));
        assertTrue(
                new TeamTool(scoped, workerContext)
                        .team(
                                "completeTeam",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)
                        .contains("not allowed"));
    }

    @Test
    void separateNativeClientsReuseMessageSequenceWithinTheSameTeam() {
        InMemoryStore sharedStore = new InMemoryStore();
        LocalTeamClient firstClient = new LocalTeamClient(sharedStore);
        LocalTeamClient restartedClient = new LocalTeamClient(sharedStore);
        firstClient
                .createTeam(
                        new TeamCreateSpec(
                                TEAM,
                                NAMESPACE,
                                "probe objective",
                                "lead-ref",
                                "lead prompt",
                                List.of(new TeamMemberSpec("worker-a", "worker-ref", "", "byo"))))
                .block();

        firstClient.sendMessage(NAMESPACE, TEAM, "lead", "worker-a", "before restart").block();
        restartedClient.sendMessage(NAMESPACE, TEAM, "lead", "worker-a", "after restart").block();

        var messages = restartedClient.listMessages(NAMESPACE, TEAM, 20).block();
        assertNotNull(messages);
        assertEquals(1, messages.size());
        assertEquals("after restart", messages.get(0).content());
    }

    private static final class CountingBudget implements TeamActionBudget {
        private final AtomicInteger tasks = new AtomicInteger();
        private final AtomicInteger recipients = new AtomicInteger();

        @Override
        public void reserveTask() {
            tasks.incrementAndGet();
        }

        @Override
        public void reserveMessageRecipients(int count) {
            recipients.addAndGet(count);
        }
    }
}
