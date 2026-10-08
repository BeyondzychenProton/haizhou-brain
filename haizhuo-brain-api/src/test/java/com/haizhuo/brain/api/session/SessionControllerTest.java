package com.haizhuo.brain.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.SessionTimelineItem;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunExecutionMode;
import com.haizhuo.brain.platform.run.RunExecutionTarget;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.platform.session.SessionRoleOption;
import com.haizhuo.brain.platform.tool.ToolApprovalService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {
    @Mock SessionApplicationService sessions;
    @Mock ToolApprovalService toolApprovals;

    @Test
    void sessionResponseExposesFixedVersion() {
        var owner = new UserId(42);
        var user = new AuthenticatedUser(owner, Set.of(PlatformRole.USER), 1, false);
        var id = new SessionId("session-fixed");
        var now = Instant.parse("2026-10-07T07:00:00Z");
        when(sessions.get(id, owner)).thenReturn(new com.haizhuo.brain.platform.session.AgentSession(
                id, owner, 1, com.haizhuo.brain.platform.session.AgentSession.Status.ACTIVE,
                now, now, 0, 7L, false));
        StepVerifier.create(new SessionController(sessions, toolApprovals).get(user, id.value()))
                .assertNext(response -> assertEquals(7L, response.definitionVersionId()))
                .verifyComplete();
    }

    @Test
    void timelineReturnsScalarRunIdsSoMessagesFromDifferentRunsDoNotCollide() {
        SessionId sessionId = new SessionId("session-1");
        UserId owner = new UserId(42);
        AuthenticatedUser user = new AuthenticatedUser(owner, Set.of(PlatformRole.USER), 1, false);
        Instant now = Instant.parse("2026-09-28T10:00:00Z");
        when(sessions.timeline(sessionId, owner, 100)).thenReturn(List.of(
                new SessionTimelineItem(new RunId("run-1"), 1, "USER_INPUT", "第一问", now),
                new SessionTimelineItem(new RunId("run-1"), 2, "RUN_COMPLETED", "第一答", now),
                new SessionTimelineItem(new RunId("run-2"), 1, "USER_INPUT", "第二问", now),
                new SessionTimelineItem(new RunId("run-2"), 2, "RUN_COMPLETED", "第二答", now)));

        StepVerifier.create(new SessionController(sessions, toolApprovals).timeline(user, sessionId.value(), 100))
                .assertNext(items -> {
                    assertEquals(4, items.size());
                    assertEquals(List.of("run-1", "run-1", "run-2", "run-2"),
                            items.stream().map(SessionController.SessionTimelineResponse::runId).toList());
                    assertEquals("coordinator", items.get(1).executorRoleId());
                })
                .verifyComplete();
    }

    @Test
    void rolesExposeOnlySafeRoleMetadataAndRunResponseShowsTheResolvedExecutor() {
        SessionId sessionId = new SessionId("session-team");
        UserId owner = new UserId(42);
        AuthenticatedUser user = new AuthenticatedUser(owner, Set.of(PlatformRole.USER), 1, false);
        when(sessions.roles(sessionId, owner)).thenReturn(List.of(
                new SessionRoleOption("coordinator", "协调员", 1L, 10L, true),
                new SessionRoleOption("researcher", "研究专家", 2L, 20L, true)));
        StepVerifier.create(new SessionController(sessions, toolApprovals).roles(user, sessionId.value()))
                .assertNext(roles -> {
                    assertEquals(List.of("coordinator", "researcher"), roles.stream()
                            .map(SessionController.SessionRoleResponse::roleId).toList());
                    assertEquals("研究专家", roles.get(1).displayName());
                })
                .verifyComplete();

        Instant now = Instant.parse("2026-10-08T00:00:00Z");
        AgentRun run = new AgentRun(new RunId("run-researcher"), sessionId, owner, 1L, 10L,
                "request-1", "digest-1", RunState.QUEUED, now, null, null);
        when(sessions.createRun(sessionId, owner, "request-1", "帮我研究", "researcher", RunExecutionMode.DIRECT, List.of()))
                .thenReturn(run);
        when(sessions.queuePosition(run.id(), owner)).thenReturn(0);
        when(sessions.executionTarget(run, owner)).thenReturn(
                new RunExecutionTarget(RunExecutionMode.DIRECT, "researcher", 2L, 20L, "slot-1"));
        StepVerifier.create(new SessionController(sessions, toolApprovals).createRun(user, sessionId.value(),
                        new SessionController.CreateRunRequest("request-1", "帮我研究", "researcher", RunExecutionMode.DIRECT)))
                .assertNext(response -> {
                    assertEquals("researcher", response.executorRoleId());
                    assertEquals(2L, response.executorEmployeeId());
                    assertEquals(20L, response.executorDefinitionVersionId());
                })
                .verifyComplete();
    }
}
