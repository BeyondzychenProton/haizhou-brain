package com.haizhuo.brain.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentResult;
import com.haizhuo.brain.platform.run.AgentResultRepository;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.RunFeedback;
import com.haizhuo.brain.platform.run.RunFeedbackRepository;
import com.haizhuo.brain.platform.run.RunFeedbackService;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunFeedbackControllerTest {
    private static final UserId OWNER = new UserId(7);
    private static final RunId RUN = new RunId("run-a");
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");

    @Test
    void submitPersistsBeforeExportAndReplayReadsSameFact() {
        MemoryRepository repository = new MemoryRepository();
        AgentResultRepository results = new AgentResultRepository() {
            @Override public Optional<AgentResult> findRoot(RunId runId, UserId owner) {
                return Optional.of(new AgentResult("result-a", RUN, "ROOT_FINAL", "text/plain", "answer", "hash",
                        6, 1, EventVisibility.USER, "attempt-a", null, Map.of(), NOW, false));
            }
            @Override public Optional<AgentResult> findOwned(String resultId, RunId runId, UserId owner) {
                return Optional.empty();
            }
        };
        SessionApplicationService sessions = new SessionApplicationService(null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC)) {
            @Override public AgentRun getRun(RunId runId, UserId owner) {
                return new AgentRun(RUN, new SessionId("session-a"), OWNER, 11, 101, "create-1", "digest",
                        RunState.SUCCEEDED, NOW, NOW, NOW, RuntimeProfile.LEGACY_STABLE);
            }
        };
        RunFeedbackService service = new RunFeedbackService(sessions, results, repository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicInteger exportAttempts = new AtomicInteger();
        RunFeedbackController controller = new RunFeedbackController(service,
                (runId, observationId, name, value, comment) -> {
                    exportAttempts.incrementAndGet();
                    return true;
                });
        AuthenticatedUser user = new AuthenticatedUser(OWNER, Set.of(PlatformRole.USER), 1, false);
        var request = new RunFeedbackController.FeedbackRequest("feedback-request", 1.0, "good");

        var created = controller.submit(user, RUN.value(), request).block();
        var replay = controller.submit(user, RUN.value(), request).block();
        assertNotNull(created);
        assertNotNull(replay);
        assertEquals(201, created.getStatusCode().value());
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(created.getBody().feedbackId(), replay.getBody().feedbackId());
        assertEquals("ACCEPTED_NOT_CONFIRMED", created.getBody().exportDisposition());
        assertEquals(1, repository.items.size());
        assertEquals(1, exportAttempts.get(), "仅首次持久化事实触发一次观测队列尝试");
        assertEquals(1, controller.list(user, RUN.value(), null, 20).block().items().size());
    }

    private static final class MemoryRepository implements RunFeedbackRepository {
        private final Map<String, RunFeedback> items = new HashMap<>();

        @Override public SaveResult saveOrGet(RunFeedback proposed) {
            String key = proposed.userId().value() + ":" + proposed.runId().value() + ":" + proposed.clientRequestId();
            RunFeedback current = items.get(key);
            if (current != null) return new SaveResult(current, false);
            items.put(key, proposed);
            return new SaveResult(proposed, true);
        }

        @Override public Optional<RunFeedback> findByRequest(UserId owner, RunId runId, String requestId) {
            return Optional.ofNullable(items.get(owner.value() + ":" + runId.value() + ":" + requestId));
        }

        @Override public Optional<RunFeedback> updateExportDisposition(UserId owner, RunId runId, String feedbackId,
                                                                       RunFeedback.ExportDisposition disposition) {
            return items.entrySet().stream().filter(entry -> entry.getValue().feedbackId().equals(feedbackId))
                    .findFirst().map(entry -> {
                        RunFeedback old = entry.getValue();
                        RunFeedback updated = new RunFeedback(old.feedbackId(), old.runId(), old.userId(),
                                old.clientRequestId(), old.requestDigest(), old.value(), old.comment(), old.createdAt(),
                                old.exportDisposition() == RunFeedback.ExportDisposition.QUEUE_STATUS_UNKNOWN
                                        ? disposition : old.exportDisposition());
                        items.put(entry.getKey(), updated);
                        return updated;
                    });
        }

        @Override public List<RunFeedback> list(UserId owner, RunId runId, Position before, int limit) {
            return items.values().stream().filter(item -> item.userId().equals(owner) && item.runId().equals(runId))
                    .sorted(Comparator.comparing(RunFeedback::createdAt).thenComparing(RunFeedback::feedbackId).reversed())
                    .limit(limit).toList();
        }
    }
}
