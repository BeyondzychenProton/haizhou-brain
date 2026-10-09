package com.haizhuo.brain.infrastructure.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.platform.run.RecoveryRunQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcRecoveryRunQueryRepositoryTest {
    private static final Instant T0 = Instant.parse("2026-10-09T04:00:00Z");
    private JdbcTemplate jdbc;
    private JdbcRecoveryRunQueryRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc("recoveryquery");
        jdbc.execute("CREATE TABLE platform_agent_run(run_id VARCHAR(36) PRIMARY KEY,session_id VARCHAR(36),"
                + "user_id BIGINT,employee_id BIGINT,definition_version_id BIGINT,runtime_profile VARCHAR(32),"
                + "state VARCHAR(32),failure_code VARCHAR(64),created_at TIMESTAMP,started_at TIMESTAMP,finished_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE platform_agent_run_event(run_id VARCHAR(36),sequence_no INT,event_type VARCHAR(64),"
                + "content VARCHAR(4000),created_at TIMESTAMP,PRIMARY KEY(run_id,sequence_no))");
        jdbc.execute("CREATE TABLE platform_run_execution_attempt(attempt_id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),"
                + "attempt_no INT,worker_id VARCHAR(128),lease_token VARCHAR(64),fence_token BIGINT,"
                + "lease_expires_at TIMESTAMP,heartbeat_at TIMESTAMP,state VARCHAR(32),started_at TIMESTAMP,finished_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE platform_run_recovery_action(request_id VARCHAR(128) PRIMARY KEY,run_id VARCHAR(36),"
                + "actor_user_id BIGINT,expected_fence_token BIGINT,stop_evidence_reference VARCHAR(512),"
                + "reason VARCHAR(500),created_at TIMESTAMP)");
        repository = new JdbcRecoveryRunQueryRepository(jdbc);
    }

    @Test
    void listsWithStableDescendingCreatedAtAndRunIdCursorAndFilters() {
        insertRun("run-a", "RECOVERY_REQUIRED", "SINGLE_SKILLED", 42, 8, T0);
        insertRun("run-z", "RECOVERY_REQUIRED", "SINGLE_SKILLED", 42, 8, T0);
        insertRun("run-m", "RECOVERY_REQUIRED", "SINGLE_SKILLED", 99, 8, T0);
        insertRun("run-y", "RECOVERY_REQUIRED", "SINGLE_SKILLED", 42, 8, T0.minusSeconds(5));
        insertRun("run-term", "TERMINATED", "SINGLE_SKILLED", 42, 8, T0);

        var filter = new RecoveryRunQuery.Filter("RECOVERY_REQUIRED", 42L, 8L, null, null);
        List<RecoveryRunQuery.Summary> page = repository.findPage(filter, null, null, 2);

        assertEquals(List.of("run-z", "run-a"), page.stream().map(RecoveryRunQuery.Summary::runId).toList());
        assertEquals("UNKNOWN", page.get(0).recoveryRequiredAtStatus());
        List<RecoveryRunQuery.Summary> next = repository.findPage(filter, page.get(1).createdAt(), page.get(1).runId(), 2);
        assertEquals(List.of("run-y"), next.stream().map(RecoveryRunQuery.Summary::runId).toList());
    }

    @Test
    void detailUsesFirstRecoveryEventAndSafeAttemptEventAndAuditProjection() {
        insertRun("run-detail", "RECOVERY_REQUIRED", "SINGLE_SKILLED", 42, 8, T0);
        jdbc.update("INSERT INTO platform_run_execution_attempt(attempt_id,run_id,attempt_no,worker_id,lease_token,"
                        + "fence_token,lease_expires_at,heartbeat_at,state,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "attempt-1", "run-detail", 1, "worker-7", "secret-lease-token", 19L,
                Timestamp.from(T0.plusSeconds(90)), Timestamp.from(T0.plusSeconds(4)), "RECOVERY_REQUIRED",
                Timestamp.from(T0.plusSeconds(1)));
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) VALUES(?,?,?,?,?)",
                "run-detail", 1, "RUN_RECOVERY_REQUIRED", "internal path D:\\private\\payload", Timestamp.from(T0.plusSeconds(5)));
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) VALUES(?,?,?,?,?)",
                "run-detail", 2, "INTERNAL_TEAM_MESSAGE", "private teammate content", Timestamp.from(T0.plusSeconds(6)));
        jdbc.update("INSERT INTO platform_run_recovery_action(request_id,run_id,actor_user_id,expected_fence_token,"
                        + "stop_evidence_reference,reason,created_at) VALUES(?,?,?,?,?,?,?)",
                "request-1", "run-detail", 9L, 19L, "secret-bearing://should-not-return", "已人工核对运维记录",
                Timestamp.from(T0.plusSeconds(7)));

        RecoveryRunQuery.Detail detail = repository.findDetail("run-detail").orElseThrow();

        assertEquals(T0.plusSeconds(5), detail.summary().recoveryRequiredAt());
        assertEquals("KNOWN", detail.summary().recoveryRequiredAtStatus());
        assertEquals("worker-7", detail.latestAttempt().workerLabel());
        assertEquals(19L, detail.terminationEligibility().expectedFenceToken());
        assertTrue(detail.terminationEligibility().allowed());
        assertTrue(detail.sessionActivity().slotOccupied());
        assertEquals("run-detail", detail.sessionActivity().activeOrQueuedRuns().get(0).runId());
        assertEquals(List.of("RUN_RECOVERY_REQUIRED"), detail.publicEventSummary().stream()
                .map(RecoveryRunQuery.PublicEvent::eventType).toList());
        assertEquals("已记录外部停止证据引用", detail.recoveryActions().get(0).stopEvidenceReferenceLabel());
        assertFalse(detail.toString().contains("secret-lease-token"));
        assertFalse(detail.toString().contains("secret-bearing://should-not-return"));
        assertFalse(detail.toString().contains("private teammate content"));
    }

    @Test
    void oldRecordAndLegacyProfileRemainUnknownAndIneligible() {
        insertRun("run-legacy", "RECOVERY_REQUIRED", "LEGACY_STABLE", 42, 8, T0);
        jdbc.update("INSERT INTO platform_run_execution_attempt(attempt_id,run_id,attempt_no,worker_id,lease_token,"
                        + "fence_token,lease_expires_at,heartbeat_at,state,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "attempt-legacy", "run-legacy", 1, "worker-old", "lease", 20L, Timestamp.from(T0.minusSeconds(1)),
                Timestamp.from(T0.minusSeconds(20)), "RECOVERY_REQUIRED", Timestamp.from(T0.minusSeconds(60)));

        RecoveryRunQuery.Detail detail = repository.findDetail("run-legacy").orElseThrow();

        assertNull(detail.summary().recoveryRequiredAt());
        assertEquals("UNKNOWN", detail.summary().recoveryRequiredAtStatus());
        assertFalse(detail.terminationEligibility().allowed());
        assertEquals("LEGACY_PROFILE", detail.terminationEligibility().reasonCode());
    }

    @Test
    void detailReportsPersistedQueuedRunWithoutPromotingIt() {
        insertRun("run-current", "TERMINATED", "SINGLE_SKILLED", 42, 8, T0);
        insertRun("run-queued", "QUEUED", "SINGLE_SKILLED", 42, 8, T0.plusSeconds(1));

        RecoveryRunQuery.Detail detail = repository.findDetail("run-current").orElseThrow();

        assertTrue(detail.sessionActivity().slotOccupied());
        assertEquals("run-queued", detail.sessionActivity().activeOrQueuedRuns().get(0).runId());
        assertEquals("QUEUED", detail.sessionActivity().activeOrQueuedRuns().get(0).state());
        assertEquals(1, detail.sessionActivity().activeOrQueuedRuns().get(0).queuePosition());
        assertEquals("QUEUED", jdbc.queryForObject("SELECT state FROM platform_agent_run WHERE run_id='run-queued'",
                String.class));
    }

    private void insertRun(String runId, String state, String profile, long userId, long employeeId, Instant createdAt) {
        jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,user_id,employee_id,definition_version_id,"
                        + "runtime_profile,state,failure_code,created_at,started_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                runId, "session-" + userId, userId, employeeId, 100L + employeeId, profile, state,
                "RUNTIME_OUTCOME_UNKNOWN", Timestamp.from(createdAt), Timestamp.from(createdAt.plusSeconds(1)), null);
    }
}
