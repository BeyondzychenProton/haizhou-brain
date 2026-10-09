package com.haizhuo.brain.infrastructure.run;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createRunAndToolTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createSessionEventTable;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.eventTypes;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRun;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRunSpec;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.transactional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.infrastructure.session.JdbcSessionEventProjector;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.runtime.api.team.TeamExecutionMember;
import com.haizhuo.brain.runtime.api.team.TeamExecutionPersistence;
import com.haizhuo.brain.runtime.api.team.TeamExecutionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTeamExecutionProgressTest {
    private static final String RUN = "run-team-progress";
    private static final String SESSION = "session-team-progress";
    private JdbcTemplate jdbc;
    private JdbcTeamExecutionPersistence persistence;
    private TeamExecutionSnapshot snapshot;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("teamprogress");
        createRunAndToolTables(jdbc);
        createSessionEventTable(jdbc);
        createTeamTables();
        insertRun(jdbc, RUN, SESSION, 7, "RUNNING");
        insertRunSpec(jdbc, RUN);
        jdbc.update("UPDATE platform_agent_run SET runtime_profile='TEAM_AUTONOMOUS_READONLY' WHERE run_id=?", RUN);
        jdbc.update("INSERT INTO platform_run_execution_attempt(attempt_id,run_id,attempt_no,worker_id,lease_token,"
                        + "fence_token,lease_expires_at,heartbeat_at,state,started_at) VALUES('attempt-team',?,1,"
                        + "'worker','lease',1,?,?, 'RUNNING',?)", RUN, Timestamp.from(Instant.now().plusSeconds(120)),
                Timestamp.from(T0), Timestamp.from(T0));
        JdbcRunEventAppender events = new JdbcRunEventAppender(jdbc, new JdbcSessionEventProjector(jdbc));
        persistence = transactional(new JdbcTeamExecutionPersistence(jdbc, events), jdbc);
        snapshot = new TeamExecutionSnapshot("team-generation", new RunId(RUN), new UserId(7),
                new SessionId(SESSION), "attempt-team", 1, "team-generation", "namespace", 1,
                List.of(new TeamExecutionMember("lead", 1, 1, "session/lead", TeamExecutionMember.Kind.LEAD),
                        new TeamExecutionMember("worker", 9, 1, "session/worker", TeamExecutionMember.Kind.WORKER)), T0);
    }

    @Test
    void lifecycleEventsAreOrdinalGuardedAndFactsNotifyInTheSameRunStream() throws Exception {
        persistence.start(snapshot);
        persistence.reserveAction(snapshot, TeamExecutionPersistence.Action.TASK_CREATED, 1, 6);

        assertTrue(persistence.recordMemberEvent(snapshot, "worker", TeamExecutionPersistence.MemberEvent.STARTED,
                2, T0.plusSeconds(2)));
        assertTrue(persistence.recordMemberEvent(snapshot, "worker", TeamExecutionPersistence.MemberEvent.ENDED,
                2, T0.plusSeconds(3)), "同一 turn ordinal 允许从 STARTED 向 ENDED 推进");
        assertTrue(persistence.recordMemberEvent(snapshot, "worker", TeamExecutionPersistence.MemberEvent.STARTED,
                1, T0.plusSeconds(1)), "迟到的较小 ordinal 被安全忽略，不代表 Run fence 丢失");
        assertTrue(persistence.recordMemberEvent(snapshot, "worker", TeamExecutionPersistence.MemberEvent.STARTED,
                3, T0.plusSeconds(4)));
        assertTrue(persistence.recordMemberEvent(snapshot, "worker", TeamExecutionPersistence.MemberEvent.STOPPED,
                3, T0.plusSeconds(5)));
        assertTrue(persistence.recordMemberEvent(snapshot, "worker", TeamExecutionPersistence.MemberEvent.ENDED,
                3, T0.plusSeconds(6)), "相同 ordinal 的重复终态被幂等忽略，不产生第二条事实或通知");
        assertTrue(persistence.complete(snapshot, "a".repeat(64)));

        assertEquals("STOPPED", jdbc.queryForObject("SELECT state FROM platform_run_team_member_binding "
                + "WHERE team_execution_id='team-generation' AND role_id='worker'", String.class));
        assertEquals(3L, jdbc.queryForObject("SELECT last_transition_ordinal FROM platform_run_team_member_binding "
                + "WHERE team_execution_id='team-generation' AND role_id='worker'", Long.class));
        assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_event "
                + "WHERE run_id=? AND event_type='RUN_PROGRESS_UPDATED'", Integer.class, RUN));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_event "
                + "WHERE run_id=? AND event_type LIKE 'INTERNAL_TEAM_MEMBER_%'", Integer.class, RUN));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_session_event "
                + "WHERE run_id=? AND event_type LIKE 'INTERNAL_TEAM_MEMBER_%' AND visibility='INTERNAL'", Integer.class, RUN));
        assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_session_event "
                + "WHERE run_id=? AND event_type='RUN_PROGRESS_UPDATED' AND visibility='USER'", Integer.class, RUN));
        assertTrue(eventTypes(jdbc, RUN).contains("RUN_PROGRESS_UPDATED"));
        String payload = jdbc.queryForObject("SELECT payload_json FROM platform_agent_run_event WHERE run_id=? "
                + "AND event_type='RUN_PROGRESS_UPDATED' ORDER BY sequence_no LIMIT 1", String.class, RUN);
        var metadata = new ObjectMapper().readTree(payload);
        assertEquals(3, metadata.size());
        assertEquals(RUN, metadata.path("runId").asText());
        assertEquals("COLLABORATION", metadata.path("projectionKind").asText());
        assertEquals(1, metadata.path("schemaVersion").asInt());
    }

    @Test
    void recoveryTransitionPublishesOneSafeProgressInvalidation() {
        persistence.start(snapshot);

        assertTrue(persistence.requireRecovery(snapshot, "TEAM_TIMEOUT"));
        assertFalse(persistence.requireRecovery(snapshot, "TEAM_TIMEOUT"));

        assertEquals("RECOVERY_REQUIRED", jdbc.queryForObject("SELECT state FROM platform_run_team_execution "
                + "WHERE team_execution_id='team-generation'", String.class));
        assertEquals("RECOVERY_REQUIRED", jdbc.queryForObject("SELECT state FROM platform_agent_run WHERE run_id=?",
                String.class, RUN));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_event "
                + "WHERE run_id=? AND event_type='RUN_PROGRESS_UPDATED'", Integer.class, RUN));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_event "
                + "WHERE run_id=? AND event_type='RUN_RECOVERY_REQUIRED'", Integer.class, RUN));
    }

    private void createTeamTables() {
        jdbc.execute("CREATE TABLE platform_run_team_execution(team_execution_id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),"
                + "user_id BIGINT,session_id VARCHAR(64),attempt_id VARCHAR(64),fence_token BIGINT,team_name VARCHAR(96),"
                + "native_namespace VARCHAR(128),state VARCHAR(24),started_at TIMESTAMP,completed_at TIMESTAMP,result_sha256 CHAR(64),"
                + "recovery_reason_code VARCHAR(64))");
        jdbc.execute("CREATE TABLE platform_run_team_member_binding(team_execution_id VARCHAR(64),role_id VARCHAR(64),"
                + "employee_id BIGINT,definition_version_id BIGINT,harness_session_key VARCHAR(160),member_kind VARCHAR(16),"
                + "state VARCHAR(16),last_transition_ordinal BIGINT NOT NULL DEFAULT 0,last_transition_phase TINYINT NOT NULL DEFAULT 0,"
                + "last_transition_at TIMESTAMP NULL,"
                + "PRIMARY KEY(team_execution_id,role_id))");
        jdbc.execute("CREATE TABLE platform_run_team_action_reservation(action_id VARCHAR(64) PRIMARY KEY,team_execution_id VARCHAR(64),"
                + "action_type VARCHAR(24),action_count INT,reserved_at TIMESTAMP)");
    }
}
