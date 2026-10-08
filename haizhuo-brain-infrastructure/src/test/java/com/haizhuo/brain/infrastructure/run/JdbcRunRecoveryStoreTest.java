package com.haizhuo.brain.infrastructure.run;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createRunAndToolTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createSessionAndGuidanceTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createSessionEventTable;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.eventTypes;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRun;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertSession;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haizhuo.brain.infrastructure.session.JdbcSessionEventProjector;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunRecoveryStore;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcRunRecoveryStoreTest {
    private static final UserId ACTOR = new UserId(7);
    private JdbcTemplate jdbc;
    private RunRecoveryStore store;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("runrecovery");
        createRunAndToolTables(jdbc);
        createSessionAndGuidanceTables(jdbc);
        createSessionEventTable(jdbc);
        insertSession(jdbc, "session-1", 42L, 1L, "ACTIVE");
        jdbc.execute("CREATE TABLE platform_run_recovery_action(request_id VARCHAR(128) PRIMARY KEY,"
                + "run_id VARCHAR(36) NOT NULL,actor_user_id BIGINT NOT NULL,expected_fence_token BIGINT NOT NULL,"
                + "stop_evidence_reference VARCHAR(512) NOT NULL,reason VARCHAR(500) NOT NULL,created_at TIMESTAMP NOT NULL)");
        insertRun(jdbc, "run-recovery", "session-1", "RECOVERY_REQUIRED");
        jdbc.update("UPDATE platform_agent_run SET runtime_profile='SINGLE_SKILLED' WHERE run_id='run-recovery'");
        jdbc.update("INSERT INTO platform_run_execution_attempt(attempt_id,run_id,attempt_no,worker_id,lease_token,"
                        + "fence_token,lease_expires_at,heartbeat_at,state,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "attempt-1", "run-recovery", 1, "worker-old", "lease-1", 3L,
                Timestamp.from(T0.plusSeconds(90)), Timestamp.from(T0), "RECOVERY_REQUIRED", Timestamp.from(T0));
        store = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.transactional(
                new JdbcRunRecoveryStore(jdbc, new JdbcRunEventAppender(jdbc, new JdbcSessionEventProjector(jdbc))), jdbc);
    }

    @Test
    void terminationRequiresMatchingFenceAndAuditsAtomicallyAndIdempotently() {
        RunId runId = new RunId("run-recovery");
        assertThrows(IllegalStateException.class, () -> store.terminate(runId, ACTOR, 2L,
                "request-1", "ops-ticket-8", "确认旧 worker 已停止"));
        assertEquals("RECOVERY_REQUIRED", state(runId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_recovery_action", Integer.class));

        AgentRun terminated = store.terminate(runId, ACTOR, 3L,
                "request-1", "ops-ticket-8", "确认旧 worker 已停止");
        assertEquals("TERMINATED", terminated.state().name());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM platform_run_execution_attempt WHERE attempt_id='attempt-1'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_recovery_action", Integer.class));
        assertEquals(java.util.List.of("RUN_RECOVERY_TERMINATED"), eventTypes(jdbc, runId.value()));

        AgentRun replay = store.terminate(runId, ACTOR, 3L,
                "request-1", "ops-ticket-8", "确认旧 worker 已停止");
        assertEquals(terminated.state(), replay.state());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_event WHERE run_id=?",
                Integer.class, runId.value()));
        assertThrows(IllegalStateException.class, () -> store.terminate(runId, ACTOR, 3L,
                "request-1", "different-ticket", "不同处置"));
    }

    @Test
    void terminalRunCannotBeTerminatedWithoutRecoveryState() {
        jdbc.update("UPDATE platform_agent_run SET state='SUCCEEDED' WHERE run_id='run-recovery'");
        assertThrows(IllegalStateException.class, () -> store.terminate(new RunId("run-recovery"), ACTOR, 3L,
                "request-2", "ops-ticket-9", "不应处置已完成运行"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_recovery_action", Integer.class));
    }

    private String state(RunId runId) {
        return jdbc.queryForObject("SELECT state FROM platform_agent_run WHERE run_id=?", String.class, runId.value());
    }
}
