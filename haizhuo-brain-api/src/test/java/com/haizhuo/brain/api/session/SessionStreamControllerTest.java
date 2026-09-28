package com.haizhuo.brain.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.SessionEvent;
import com.haizhuo.brain.platform.run.SessionEventPage;
import com.haizhuo.brain.platform.session.AgentSession;
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
class SessionStreamControllerTest {
    private static final SessionId SESSION = new SessionId("session-1");
    private static final RunId RUN = new RunId("run-1");
    private static final UserId OWNER = new UserId(42);
    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");

    @Mock SessionApplicationService sessions;
    private SessionStreamController controller;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        controller = new SessionStreamController(sessions, new RunRealtimeEventHub());
        user = new AuthenticatedUser(OWNER, Set.of(PlatformRole.USER), 1, false);
    }

    @Test
    void eventsProjectSessionCursorIntoTheUnifiedEnvelope() {
        SessionEvent event = new SessionEvent(SESSION, 7L, RUN, 2, "RUN_COMPLETED", EventVisibility.USER, "结果", T0);
        when(sessions.sessionEventPage(SESSION, OWNER, 5L, 200))
                .thenReturn(new SessionEventPage(List.of(event), 1L, false));

        StepVerifier.create(controller.events(user, SESSION.value(), 5L, 200))
                .assertNext(response -> {
                    assertEquals(1, response.events().size());
                    assertEquals(1L, response.cursorFloor());
                    assertEquals(false, response.cursorExpired());
                    StreamEvent envelope = response.events().get(0);
                    assertEquals("session-1:7", envelope.eventId());
                    assertEquals(SESSION.value(), envelope.sessionId());
                    assertEquals(7L, envelope.sessionCursor());
                    assertEquals(RUN.value(), envelope.runId());
                    assertEquals(2, envelope.runSequence());
                    assertEquals("USER", envelope.visibility());
                    assertEquals("durable", envelope.durability());
                    assertEquals("run-1-assistant", envelope.payload().messageId());
                    assertEquals("结果", envelope.payload().text());
                })
                .verifyComplete();
    }

    @Test
    void expiredCursorIsReportedInsteadOfSilentlyReturningAnEmptyBatch() {
        // 空批次与"确实没有新事件"在载荷上无法区分；失效必须显式带出，客户端才能转全量恢复。
        when(sessions.sessionEventPage(SESSION, OWNER, 3L, 200))
                .thenReturn(new SessionEventPage(List.of(), 101L, true));

        StepVerifier.create(controller.events(user, SESSION.value(), 3L, 200))
                .assertNext(response -> {
                    assertTrue(response.events().isEmpty());
                    assertEquals(101L, response.cursorFloor());
                    assertTrue(response.cursorExpired());
                })
                .verifyComplete();
    }

    @Test
    void streamResumesFromLastEventIdAndStaysOpenAcrossRuns() {
        when(sessions.get(SESSION, OWNER)).thenReturn(new AgentSession(SESSION, OWNER, 1L,
                AgentSession.Status.ACTIVE, T0, T0, 0));
        SessionEvent completed = new SessionEvent(SESSION, 3L, RUN, 2, "RUN_COMPLETED", EventVisibility.USER,
                "第一轮结果", T0.plusSeconds(1));
        when(sessions.sessionEventsOfOwnedSession(SESSION, OWNER, 2L, 200)).thenReturn(List.of(completed));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
                "/api/v1/sessions/session-1/stream?after=1"));

        // 会话流不因某个 Run 的终态结束，后续 Run 仍复用同一条连接，所以这里取消而不是等完成。
        StepVerifier.create(controller.stream(user, SESSION.value(), 1L, "2", exchange))
                .assertNext(event -> {
                    assertEquals("3", event.id());
                    assertEquals("session-1", event.data().sessionId());
                    assertEquals(3L, event.data().sessionCursor());
                    assertEquals("RUN_COMPLETED", event.data().type());
                })
                .thenCancel()
                .verify();

        verify(sessions).get(SESSION, OWNER);
        verify(sessions).sessionEventsOfOwnedSession(SESSION, OWNER, 2L, 200);
    }

    @Test
    void rejectsStreamBeforeSubscribingWhenSessionIsNotOwned() {
        when(sessions.get(SESSION, OWNER)).thenThrow(new IllegalArgumentException("Session was not found"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
                "/api/v1/sessions/session-1/stream"));

        StepVerifier.create(controller.stream(user, SESSION.value(), null, null, exchange))
                .expectErrorMatches(error -> error instanceof IllegalArgumentException
                        && "Session was not found".equals(error.getMessage()))
                .verify();

        verify(sessions, never()).sessionEventsOfOwnedSession(SESSION, OWNER, 0L, 200);
    }
}
