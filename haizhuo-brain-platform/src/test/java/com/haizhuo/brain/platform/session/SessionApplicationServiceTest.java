package com.haizhuo.brain.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.HarnessRunSpec;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.SessionEvent;
import com.haizhuo.brain.platform.run.SessionRunStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 会话游标保留窗口（P2）：续传游标早于保留下界时必须显式宣告失效，而不是返回空批次——
 * 空批次与"确实没有新事件"在载荷上无法区分，客户端会永久停在过期快照上。
 */
class SessionApplicationServiceTest {
    private static final UserId OWNER = new UserId(42);
    private static final SessionId SESSION = new SessionId("session-1");
    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");

    @Test
    void cursorNeverExpiresWhileNothingHasBeenTrimmed() {
        SessionApplicationService service = service(new FakeStore(0L, List.of()));

        assertFalse(service.sessionEventPage(SESSION, OWNER, 500L, 200).cursorExpired(),
                "从未裁剪时下界为 0，任何游标都不应被判为失效");
        assertEquals(0L, service.sessionEventPage(SESSION, OWNER, 500L, 200).cursorFloor());
    }

    @Test
    void cursorExpiresOnlyWhenItFallsBeforeTheRetentionFloor() {
        SessionApplicationService service = service(new FakeStore(101L, List.of()));

        assertTrue(service.sessionEventPage(SESSION, OWNER, 50L, 200).cursorExpired(),
                "请求游标 50 之后、下界 101 之前的事件已被裁剪");
        assertTrue(service.sessionEventPage(SESSION, OWNER, 99L, 200).cursorExpired());
        assertFalse(service.sessionEventPage(SESSION, OWNER, 100L, 200).cursorExpired(),
                "请求游标恰为下界前一条时仍可无缝续传");
        assertFalse(service.sessionEventPage(SESSION, OWNER, 101L, 200).cursorExpired());
        assertFalse(service.sessionEventPage(SESSION, OWNER, 0L, 200).cursorExpired(),
                "全量补读请求天然不涉及失效");
    }

    @Test
    void ownershipIsCheckedBeforeTheRetentionWindowIsRead() {
        FakeStore store = new FakeStore(101L, List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service(store).sessionEventPage(SESSION, new UserId(99), 3L, 200));
        assertEquals(0, store.floorReads, "非属主不得读到他人会话的保留窗口");
    }

    @Test
    void pageCarriesEventsAndFloorTogether() {
        SessionEvent event = new SessionEvent(SESSION, 102L, new RunId("run-1"), 1, "RUN_COMPLETED",
                EventVisibility.USER, "结果", T0);
        SessionApplicationService service = service(new FakeStore(101L, List.of(event)));

        var page = service.sessionEventPage(SESSION, OWNER, 101L, 200);

        assertEquals(List.of(event), page.events());
        assertEquals(101L, page.cursorFloor());
    }

    private static SessionApplicationService service(SessionRunStore store) {
        return new SessionApplicationService(store, null, null, null, Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static final class FakeStore implements SessionRunStore {
        private final long floor;
        private final List<SessionEvent> events;
        private int floorReads;

        private FakeStore(long floor, List<SessionEvent> events) {
            this.floor = floor;
            this.events = events;
        }

        @Override public AgentSession createSession(AgentSession session) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<AgentSession> findSession(SessionId sessionId, UserId owner) {
            return SESSION.equals(sessionId) && OWNER.equals(owner)
                    ? Optional.of(new AgentSession(SESSION, OWNER, 1L, AgentSession.Status.ACTIVE, T0, T0, 0))
                    : Optional.empty();
        }

        @Override public AgentRun createRun(AgentRun run, String input, HarnessRunSpec runSpec) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<AgentRun> findRun(RunId runId, UserId owner) {
            return Optional.empty();
        }

        @Override public List<RunEvent> findEvents(RunId runId, UserId owner, int afterSequence, int limit) {
            return List.of();
        }

        @Override public List<SessionEvent> findSessionEvents(SessionId sessionId, UserId owner,
                                                              long afterCursor, int limit) {
            return events;
        }

        @Override public long oldestSessionCursor(SessionId sessionId, UserId owner) {
            floorReads++;
            return floor;
        }
    }
}
