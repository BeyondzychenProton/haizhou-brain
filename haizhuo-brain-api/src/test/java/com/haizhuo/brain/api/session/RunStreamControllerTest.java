package com.haizhuo.brain.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class RunStreamControllerTest {
    private static final RunId RUN = new RunId("run-1");
    private static final UserId OWNER = new UserId(42);
    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");

    @Mock SessionApplicationService sessions;
    private RunStreamController controller;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        controller = new RunStreamController(sessions, new RunRealtimeEventHub());
        user = new AuthenticatedUser(OWNER, Set.of(PlatformRole.USER), 1, false);
    }

    @Test
    void authorizesOwnerAndResumesFromGreatestCursor() {
        AgentRun run = new AgentRun(RUN, new SessionId("session-1"), OWNER, 1L, 7L,
                "request-1", "d".repeat(64), RunState.RUNNING, T0, T0, null);
        RunEvent completed = new RunEvent(RUN, 4, "RUN_COMPLETED", "完成", T0.plusSeconds(1));
        when(sessions.getRun(RUN, OWNER)).thenReturn(run);
        when(sessions.events(RUN, OWNER, 3, 200)).thenReturn(List.of(completed));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
                "/api/v1/sessions/runs/run-1/stream?after=1"));

        StepVerifier.create(controller.stream(user, RUN.value(), 1, "3", exchange))
                .assertNext(event -> {
                    assertEquals("4", event.id());
                    assertEquals("RUN_COMPLETED", event.data().type());
                    assertEquals("durable", event.data().durability());
                    assertEquals("完成", event.data().payload().text());
                })
                .verifyComplete();

        verify(sessions).getRun(RUN, OWNER);
        verify(sessions).events(RUN, OWNER, 3, 200);
        assertEquals("no-cache", exchange.getResponse().getHeaders().getCacheControl());
        assertEquals("no", exchange.getResponse().getHeaders().getFirst("X-Accel-Buffering"));
    }

    @Test
    void rejectsStreamBeforeSubscribingWhenRunIsNotOwned() {
        when(sessions.getRun(RUN, OWNER)).thenThrow(new IllegalArgumentException("Run was not found"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
                "/api/v1/sessions/runs/run-1/stream"));

        StepVerifier.create(controller.stream(user, RUN.value(), null, null, exchange))
                .expectErrorMatches(error -> error instanceof IllegalArgumentException
                        && "Run was not found".equals(error.getMessage()))
                .verify();

        verify(sessions, never()).events(RUN, OWNER, 0, 200);
    }
}
