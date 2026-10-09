package com.haizhuo.brain.infrastructure.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.RunFeedback;
import com.haizhuo.brain.platform.run.RunFeedbackRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcRunFeedbackRepositoryTest {
    private static final Instant T0 = Instant.parse("2026-10-09T04:00:00Z");
    private JdbcTemplate jdbc;
    private JdbcRunFeedbackRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc("runfeedback");
        jdbc.execute("CREATE TABLE platform_run_feedback(feedback_id VARCHAR(36) PRIMARY KEY,run_id VARCHAR(36),"
                + "user_id BIGINT,client_request_id VARCHAR(128),request_digest CHAR(64),score_value DOUBLE,"
                + "feedback_comment VARCHAR(1000),created_at TIMESTAMP,export_disposition VARCHAR(32),"
                + "UNIQUE(user_id,run_id,client_request_id))");
        repository = new JdbcRunFeedbackRepository(jdbc);
    }

    @Test
    void uniqueRequestReplayReturnsOriginalFactAndExportStateMovesOnlyOnce() {
        RunFeedback first = item("feedback-a", "run-a", 7, "request-a", T0,
                RunFeedback.ExportDisposition.QUEUE_STATUS_UNKNOWN);
        assertTrue(repository.saveOrGet(first).created());
        RunFeedback replay = item("feedback-b", "run-a", 7, "request-a", T0.plusSeconds(1),
                RunFeedback.ExportDisposition.QUEUE_STATUS_UNKNOWN);
        var saved = repository.saveOrGet(replay);
        assertFalse(saved.created());
        assertEquals("feedback-a", saved.feedback().feedbackId());

        var updated = repository.updateExportDisposition(new UserId(7), new RunId("run-a"), "feedback-a",
                RunFeedback.ExportDisposition.ACCEPTED_NOT_CONFIRMED).orElseThrow();
        assertEquals(RunFeedback.ExportDisposition.ACCEPTED_NOT_CONFIRMED, updated.exportDisposition());
        var replayedUpdate = repository.updateExportDisposition(new UserId(7), new RunId("run-a"), "feedback-a",
                RunFeedback.ExportDisposition.NOT_ACCEPTED).orElseThrow();
        assertEquals(RunFeedback.ExportDisposition.ACCEPTED_NOT_CONFIRMED, replayedUpdate.exportDisposition());
        assertEquals("first comment", replayedUpdate.comment());
    }

    @Test
    void historyUsesOwnerScopedStableKeysetOrderAndOmitsOtherRuns() {
        repository.saveOrGet(item("feedback-a", "run-a", 7, "request-a", T0,
                RunFeedback.ExportDisposition.NOT_ACCEPTED));
        repository.saveOrGet(item("feedback-z", "run-a", 7, "request-z", T0,
                RunFeedback.ExportDisposition.QUEUE_STATUS_UNKNOWN));
        repository.saveOrGet(item("feedback-private", "run-a", 8, "request-p", T0,
                RunFeedback.ExportDisposition.NOT_ACCEPTED));
        repository.saveOrGet(item("feedback-other", "run-b", 7, "request-b", T0,
                RunFeedback.ExportDisposition.NOT_ACCEPTED));

        var first = repository.list(new UserId(7), new RunId("run-a"), null, 1);
        assertEquals("feedback-z", first.get(0).feedbackId());
        var next = repository.list(new UserId(7), new RunId("run-a"),
                new RunFeedbackRepository.Position(T0, "feedback-z"), 10);
        assertEquals(java.util.List.of("feedback-a"), next.stream().map(RunFeedback::feedbackId).toList());
    }

    private static RunFeedback item(String id, String runId, long owner, String requestId, Instant createdAt,
                                    RunFeedback.ExportDisposition disposition) {
        return new RunFeedback(id, new RunId(runId), new UserId(owner), requestId, "digest-" + requestId,
                1.0, "first comment", createdAt, disposition);
    }
}
