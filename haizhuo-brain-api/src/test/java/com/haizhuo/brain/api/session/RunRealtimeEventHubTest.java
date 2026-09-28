package com.haizhuo.brain.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class RunRealtimeEventHubTest {
    private static final SessionId SESSION = new SessionId("session-1");
    private static final RunId RUN = new RunId("run-1");
    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");

    @Test
    void publishesRunScopedTextDeltasWithAttemptLocalOffsets() {
        RunRealtimeEventHub hub = new RunRealtimeEventHub();

        StepVerifier.create(hub.stream(RUN).take(3))
                .then(() -> {
                    hub.publishTextDelta(SESSION, RUN, "attempt-1", 1, "你", T0);
                    hub.publishTextDelta(SESSION, RUN, "attempt-1", 2, "好", T0.plusMillis(1));
                    hub.publishTextDelta(SESSION, RUN, "attempt-2", 1, "新", T0.plusSeconds(1));
                })
                .assertNext(event -> {
                    assertEquals(SESSION, event.sessionId());
                    assertEquals("attempt-1", event.attemptId());
                    assertEquals(1L, event.streamOffset());
                    assertEquals("message.text.delta", event.type());
                    assertEquals("你", event.content());
                    assertEquals("run-1-assistant", event.messageId());
                })
                .assertNext(event -> {
                    assertEquals("attempt-1", event.attemptId());
                    assertEquals(2L, event.streamOffset());
                    assertEquals("好", event.content());
                })
                .assertNext(event -> {
                    assertEquals("attempt-2", event.attemptId());
                    assertEquals(1L, event.streamOffset());
                    assertEquals("新", event.content());
                })
                .verifyComplete();
    }

    @Test
    void publishesSessionScopedDeltasAcrossRuns() {
        RunRealtimeEventHub hub = new RunRealtimeEventHub();
        RunId other = new RunId("run-2");

        StepVerifier.create(hub.streamBySession(SESSION).take(2))
                .then(() -> {
                    hub.publishTextDelta(SESSION, RUN, "attempt-1", 1, "你", T0);
                    hub.publishTextDelta(new SessionId("session-2"), other, "attempt-1", 1, "别的会话", T0);
                    hub.publishTextDelta(SESSION, other, "attempt-1", 1, "第二轮", T0.plusSeconds(1));
                })
                .assertNext(event -> assertEquals(RUN, event.runId()))
                .assertNext(event -> assertEquals(other, event.runId()))
                .verifyComplete();
    }
}
