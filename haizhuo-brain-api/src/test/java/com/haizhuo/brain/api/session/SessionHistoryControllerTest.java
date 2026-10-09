package com.haizhuo.brain.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.session.SessionHistoryNotFoundException;
import com.haizhuo.brain.platform.session.SessionHistoryQuery;
import com.haizhuo.brain.platform.session.SessionHistoryQueryService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SessionHistoryControllerTest {
    @Test
    void pageUsesAuthenticatedOwnerAndKeepsLegacyArraysOutsideNewEnvelope() {
        SessionHistoryQuery query = new SessionHistoryQuery() {
            @Override public boolean ownsSession(long ownerUserId, String sessionId) { return ownerUserId == 9; }
            @Override public List<SessionItem> findSessions(SessionFilter filter, Position before, int limit) {
                assertEquals(9, filter.ownerUserId());
                assertEquals(2, limit);
                return List.of(new SessionItem("session-owned", 12, 101L, "CLOSED", Instant.EPOCH, Instant.EPOCH));
            }
            @Override public List<RunItem> findRuns(RunFilter filter, Position before, int limit) { return List.of(); }
            @Override public List<ResultItem> findResults(ResultFilter filter, Position before, int limit) { return List.of(); }
        };
        var controller = new SessionHistoryController(new SessionHistoryQueryService(query), null, null);
        var user = new AuthenticatedUser(new UserId(9), Set.of(PlatformRole.USER), 1, false);

        var response = controller.sessions(user, null, null, null, 1).block();

        assertEquals("session-owned", response.items().get(0).sessionId());
        assertFalse(response.hasMore());
        assertEquals(null, response.nextCursor());
    }

    @Test
    void nonOwnerSessionIsMappedToNonEnumerating404() {
        SessionHistoryQuery query = new SessionHistoryQuery() {
            @Override public boolean ownsSession(long ownerUserId, String sessionId) { return false; }
            @Override public List<SessionItem> findSessions(SessionFilter filter, Position before, int limit) { return List.of(); }
            @Override public List<RunItem> findRuns(RunFilter filter, Position before, int limit) { throw new AssertionError("must not read"); }
            @Override public List<ResultItem> findResults(ResultFilter filter, Position before, int limit) { throw new AssertionError("must not read"); }
        };
        var controller = new SessionHistoryController(new SessionHistoryQueryService(query), null, null);
        var user = new AuthenticatedUser(new UserId(9), Set.of(PlatformRole.USER), 1, false);

        var failure = assertThrows(SessionHistoryNotFoundException.class,
                () -> controller.results(user, "private-session", null, null).block());
        var response = controller.notFound(failure).block();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("RESOURCE_NOT_FOUND", response.getBody().code());
    }
}
