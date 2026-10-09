package com.haizhuo.brain.platform.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.platform.session.SessionHistoryNotFoundException;
import com.haizhuo.brain.platform.run.RunFeedback.ExportDisposition;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunFeedbackServiceTest {
    private static final UserId OWNER = new UserId(7);
    private static final RunId RUN_ID = new RunId("run-a");
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");

    @Test
    void sameRequestAndNormalizedValueReplayOneImmutableFact() {
        Fixture fixture = new Fixture();
        var first = fixture.service.submit(OWNER, RUN_ID.value(), "request-1", 1.0, "looks good");
        var replay = fixture.service.submit(OWNER, RUN_ID.value(), "request-1", 1.0, "looks good");

        assertTrue(first.created());
        assertFalse(replay.created());
        assertEquals(first.feedback().feedbackId(), replay.feedback().feedbackId());
        assertEquals(ExportDisposition.QUEUE_STATUS_UNKNOWN, replay.feedback().exportDisposition());
        assertEquals(1, fixture.repository.items.size());
    }

    @Test
    void reusedRequestWithDifferentPayloadConflictsAndRequiresCompleteSuccess() {
        Fixture fixture = new Fixture();
        fixture.service.submit(OWNER, RUN_ID.value(), "request-1", 1.0, "first");
        assertThrows(IllegalStateException.class,
                () -> fixture.service.submit(OWNER, RUN_ID.value(), "request-1", 0.0, "changed"));

        fixture.run = run(RunState.FAILED, OWNER);
        assertThrows(RunFeedbackPreconditionException.class,
                () -> fixture.service.submit(OWNER, RUN_ID.value(), "request-2", 1.0, null));
        fixture.run = run(RunState.SUCCEEDED, OWNER);
        fixture.result = Optional.of(result(true));
        assertThrows(RunFeedbackPreconditionException.class,
                () -> fixture.service.submit(OWNER, RUN_ID.value(), "request-3", 1.0, null));
        assertEquals(1, fixture.repository.items.size());
    }

    @Test
    void ownerBoundCursorPagesNewestFirstAndRejectsDifferentRun() {
        Fixture fixture = new Fixture();
        fixture.service.submit(OWNER, RUN_ID.value(), "request-a", 0.0, null);
        fixture.setClock(Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        fixture.service.submit(OWNER, RUN_ID.value(), "request-b", 1.0, null);
        fixture.setClock(Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        fixture.service.submit(OWNER, RUN_ID.value(), "request-c", 1.0, null);

        var first = fixture.service.list(OWNER, RUN_ID.value(), null, 2);
        assertEquals(List.of("request-c", "request-b"), first.items().stream()
                .map(RunFeedback::clientRequestId).toList());
        var second = fixture.service.list(OWNER, RUN_ID.value(), first.nextCursor(), 2);
        assertEquals(List.of("request-a"), second.items().stream().map(RunFeedback::clientRequestId).toList());
        assertFalse(second.hasMore());
        assertThrows(SessionHistoryNotFoundException.class, () -> fixture.service.list(OWNER,
                "run-other", first.nextCursor(), 2));
    }

    @Test
    void feedbackReadAndWriteHideRunsOwnedByAnotherUser() {
        Fixture fixture = new Fixture();
        assertThrows(SessionHistoryNotFoundException.class, () -> fixture.service.submit(new UserId(8),
                RUN_ID.value(), "request-1", 1.0, null));
        assertThrows(SessionHistoryNotFoundException.class, () -> fixture.service.list(new UserId(8),
                RUN_ID.value(), null, 20));
        assertTrue(fixture.repository.items.isEmpty());
    }

    private static AgentRun run(RunState state, UserId owner) {
        return new AgentRun(RUN_ID, new SessionId("session-a"), owner, 11, 101, "create-1", "digest",
                state, NOW, NOW, state == RunState.SUCCEEDED ? NOW : null, RuntimeProfile.LEGACY_STABLE);
    }

    private static AgentResult result(boolean legacy) {
        return new AgentResult("result-a", RUN_ID, "ROOT_FINAL", "text/plain", "complete answer", "abc123",
                15, 1, EventVisibility.USER, "attempt-a", null, Map.of(), NOW, legacy);
    }

    private static final class Fixture {
        private AgentRun run = run(RunState.SUCCEEDED, OWNER);
        private Optional<AgentResult> result = Optional.of(result(false));
        private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        private final MemoryRepository repository = new MemoryRepository();
        private final SessionApplicationService sessions = new SessionApplicationService(null, null, null, null,
                null, Clock.fixed(NOW, ZoneOffset.UTC)) {
            @Override public AgentRun getRun(RunId runId, UserId owner) {
                if (!RUN_ID.equals(runId) || run.userId().value() != owner.value())
                    throw new IllegalArgumentException("Run was not found");
                return run;
            }
        };
        private final AgentResultRepository results = new AgentResultRepository() {
            @Override public Optional<AgentResult> findRoot(RunId runId, UserId owner) { return result; }
            @Override public Optional<AgentResult> findOwned(String id, RunId runId, UserId owner) { return Optional.empty(); }
        };
        private RunFeedbackService service = createService();

        private RunFeedbackService createService() {
            return new RunFeedbackService(sessions, results, repository, clock);
        }

        private void setClock(Clock updated) {
            clock = updated;
            service = createService();
        }
    }

    private static final class MemoryRepository implements RunFeedbackRepository {
        private final Map<String, RunFeedback> items = new HashMap<>();

        @Override public SaveResult saveOrGet(RunFeedback proposed) {
            String key = key(proposed.userId(), proposed.runId(), proposed.clientRequestId());
            RunFeedback old = items.get(key);
            if (old != null) return new SaveResult(old, false);
            items.put(key, proposed);
            return new SaveResult(proposed, true);
        }

        @Override public Optional<RunFeedback> findByRequest(UserId owner, RunId runId, String requestId) {
            return Optional.ofNullable(items.get(key(owner, runId, requestId)));
        }

        @Override public Optional<RunFeedback> updateExportDisposition(UserId owner, RunId runId, String feedbackId,
                                                                       ExportDisposition disposition) {
            return items.values().stream().filter(item -> item.userId().equals(owner) && item.runId().equals(runId)
                    && item.feedbackId().equals(feedbackId)).findFirst().map(item -> {
                RunFeedback updated = new RunFeedback(item.feedbackId(), item.runId(), item.userId(),
                        item.clientRequestId(), item.requestDigest(), item.value(), item.comment(), item.createdAt(),
                        item.exportDisposition() == ExportDisposition.QUEUE_STATUS_UNKNOWN
                                ? disposition : item.exportDisposition());
                items.put(key(owner, runId, item.clientRequestId()), updated);
                return updated;
            });
        }

        @Override public List<RunFeedback> list(UserId owner, RunId runId, Position before, int limit) {
            return items.values().stream().filter(item -> item.userId().equals(owner) && item.runId().equals(runId))
                    .filter(item -> before == null || item.createdAt().isBefore(before.createdAt())
                            || item.createdAt().equals(before.createdAt()) && item.feedbackId().compareTo(before.feedbackId()) < 0)
                    .sorted(Comparator.comparing(RunFeedback::createdAt).thenComparing(RunFeedback::feedbackId).reversed())
                    .limit(limit).toList();
        }

        private static String key(UserId owner, RunId runId, String requestId) {
            return owner.value() + "|" + runId.value() + "|" + requestId;
        }
    }
}
