package com.haizhuo.brain.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionHistoryQueryServiceTest {
    private static final Instant T0 = Instant.parse("2026-10-09T04:00:00Z");

    @Test
    void sessionCursorUsesStableCreatedAtAndIdAndBindsOwnerAndFilters() {
        var query = new MemoryQuery();
        query.sessions = List.of(session("session-z", T0), session("session-a", T0), session("session-old", T0.minusSeconds(1)));
        var service = new SessionHistoryQueryService(query);

        var first = service.sessions(new UserId(7), new SessionHistoryQueryService.SessionFilter(null, null, null, 2));

        assertEquals(List.of("session-z", "session-a"), first.items().stream()
                .map(SessionHistoryQuery.SessionItem::sessionId).toList());
        assertTrue(first.hasMore());
        assertTrue(first.nextCursor() != null && !first.nextCursor().contains("session-a"));

        var next = service.sessions(new UserId(7), new SessionHistoryQueryService.SessionFilter(
                null, null, first.nextCursor(), 2));
        assertEquals(List.of("session-old"), next.items().stream().map(SessionHistoryQuery.SessionItem::sessionId).toList());
        assertFalse(next.hasMore());

        assertThrows(IllegalArgumentException.class, () -> service.sessions(new UserId(8),
                new SessionHistoryQueryService.SessionFilter(null, null, first.nextCursor(), 2)));
        assertThrows(IllegalArgumentException.class, () -> service.sessions(new UserId(7),
                new SessionHistoryQueryService.SessionFilter(100L, null, first.nextCursor(), 2)));
    }

    @Test
    void childPagesCheckSessionOwnershipBeforeReadingRunsOrResults() {
        var query = new MemoryQuery();
        query.ownedSessions = false;
        var service = new SessionHistoryQueryService(query);

        assertThrows(SessionHistoryNotFoundException.class, () -> service.runs(new UserId(7), "secret-session",
                new SessionHistoryQueryService.RunFilter(null, null, 20)));
        assertThrows(SessionHistoryNotFoundException.class, () -> service.results(new UserId(7), "secret-session",
                new SessionHistoryQueryService.ResultFilter(null, 20)));
        assertEquals(0, query.runReads);
        assertEquals(0, query.resultReads);
    }

    @Test
    void filtersAndLimitsRejectUnknownValues() {
        var service = new SessionHistoryQueryService(new MemoryQuery());
        assertThrows(IllegalArgumentException.class, () -> service.sessions(new UserId(7),
                new SessionHistoryQueryService.SessionFilter(null, "DELETED", null, 20)));
        assertThrows(IllegalArgumentException.class, () -> service.sessions(new UserId(7),
                new SessionHistoryQueryService.SessionFilter(null, null, null, 101)));
        assertThrows(IllegalArgumentException.class, () -> service.runs(new UserId(7), "session-1",
                new SessionHistoryQueryService.RunFilter("PRIVATE_STATE", null, 20)));
    }

    private static SessionHistoryQuery.SessionItem session(String id, Instant createdAt) {
        return new SessionHistoryQuery.SessionItem(id, 11, 101L, "CLOSED", createdAt, createdAt.plusSeconds(5));
    }

    private static final class MemoryQuery implements SessionHistoryQuery {
        private List<SessionItem> sessions = List.of();
        private boolean ownedSessions = true;
        private int runReads;
        private int resultReads;

        @Override public boolean ownsSession(long ownerUserId, String sessionId) { return ownedSessions; }
        @Override public List<SessionItem> findSessions(SessionFilter filter, Position before, int limit) {
            List<SessionItem> filtered = sessions.stream().filter(item -> before == null
                    || item.createdAt().isBefore(before.createdAt())
                    || (item.createdAt().equals(before.createdAt()) && item.sessionId().compareTo(before.id()) < 0))
                    .toList();
            return filtered.size() <= limit ? filtered : filtered.subList(0, limit);
        }
        @Override public List<RunItem> findRuns(RunFilter filter, Position before, int limit) {
            runReads++;
            return new ArrayList<>();
        }
        @Override public List<ResultItem> findResults(ResultFilter filter, Position before, int limit) {
            resultReads++;
            return new ArrayList<>();
        }
    }
}
