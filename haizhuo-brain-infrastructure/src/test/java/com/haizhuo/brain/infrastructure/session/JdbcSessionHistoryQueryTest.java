package com.haizhuo.brain.infrastructure.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.platform.session.SessionHistoryQuery;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcSessionHistoryQueryTest {
    private static final Instant T0 = Instant.parse("2026-10-09T04:00:00Z");
    private JdbcTemplate jdbc;
    private JdbcSessionHistoryQuery query;

    @BeforeEach
    void setUp() {
        jdbc = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc("sessionhistory");
        jdbc.execute("CREATE TABLE platform_agent_session(session_id VARCHAR(36) PRIMARY KEY,user_id BIGINT,"
                + "employee_id BIGINT,status VARCHAR(24),definition_version_id BIGINT,created_at TIMESTAMP,last_active_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE platform_agent_run(run_id VARCHAR(36) PRIMARY KEY,session_id VARCHAR(36),user_id BIGINT,"
                + "employee_id BIGINT,definition_version_id BIGINT,state VARCHAR(32),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE platform_agent_run_execution_target(run_id VARCHAR(36) PRIMARY KEY,execution_mode VARCHAR(32),"
                + "role_id VARCHAR(64),employee_id BIGINT,definition_version_id BIGINT)");
        jdbc.execute("CREATE TABLE platform_agent_result(result_id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(36),kind VARCHAR(32),"
                + "media_type VARCHAR(64),body LONGVARCHAR,body_sha256 CHAR(64),byte_size BIGINT,visibility VARCHAR(32),"
                + "created_at TIMESTAMP,executor_role_id VARCHAR(64),employee_id BIGINT,definition_version_id BIGINT)");
        insertSession("session-a", 7, 11, "ACTIVE", T0);
        insertSession("session-b", 8, 11, "ACTIVE", T0);
        query = new JdbcSessionHistoryQuery(jdbc);
    }

    @Test
    void sessionPagesAreOwnerScopedFilteredAndStableForSameTimestamp() {
        insertSession("session-z", 7, 11, "CLOSED", T0);
        insertSession("session-y", 7, 12, "CLOSED", T0);
        insertSession("session-m", 7, 11, "CLOSED", T0);

        var filter = new SessionHistoryQuery.SessionFilter(7, 11L, "CLOSED");
        var first = query.findSessions(filter, null, 2);
        assertEquals(java.util.List.of("session-z", "session-m"), first.stream()
                .map(SessionHistoryQuery.SessionItem::sessionId).toList());
        var next = query.findSessions(filter, new SessionHistoryQuery.Position(T0, "session-m"), 2);
        assertTrue(next.isEmpty());
        assertFalse(query.ownsSession(7, "session-b"));
        assertTrue(query.ownsSession(8, "session-b"));
    }

    @Test
    void runPagesExposeFrozenExecutorAndQueuePositionOnly() {
        insertRun("run-a", "session-a", 7, "QUEUED", T0, 11, 101);
        insertRun("run-z", "session-a", 7, "QUEUED", T0, 11, 101);
        insertRun("run-private", "session-b", 8, "SUCCEEDED", T0, 11, 101);
        jdbc.update("INSERT INTO platform_agent_run_execution_target(run_id,execution_mode,role_id,employee_id,definition_version_id) "
                        + "VALUES(?,?,?,?,?)", "run-z", "DIRECT", "expert", 22, 202);

        var rows = query.findRuns(new SessionHistoryQuery.RunFilter(7, "session-a", null), null, 10);

        assertEquals(java.util.List.of("run-z", "run-a"), rows.stream().map(SessionHistoryQuery.RunItem::runId).toList());
        assertEquals(2, rows.get(0).queuePosition());
        assertEquals("expert", rows.get(0).executorRoleId());
        assertEquals(22, rows.get(0).executorEmployeeId());
        assertEquals(202, rows.get(0).executorDefinitionVersionId());
        assertEquals("DIRECT", rows.get(0).mode());
        assertEquals("coordinator", rows.get(1).executorRoleId());
        assertEquals(1, rows.get(1).queuePosition());
    }

    @Test
    void resultPagesReturnOnlySuccessfulUserVisibleMetadataWithoutBody() {
        insertRun("run-success", "session-a", 7, "SUCCEEDED", T0, 11, 101);
        insertRun("run-failed", "session-a", 7, "FAILED", T0.plusSeconds(1), 11, 101);
        insertRun("run-other", "session-b", 8, "SUCCEEDED", T0.plusSeconds(2), 11, 101);
        insertResult("result-a", "run-success", "USER", "private complete answer body", T0, "coordinator");
        insertResult("result-internal", "run-success", "INTERNAL", "private delegation body", T0.plusSeconds(1), "expert");
        insertResult("result-failed", "run-failed", "USER", "failed run should not be referenceable", T0.plusSeconds(2), "coordinator");
        insertResult("result-other", "run-other", "USER", "other owner's answer", T0.plusSeconds(3), "coordinator");

        var rows = query.findResults(new SessionHistoryQuery.ResultFilter(7, "session-a"), null, 20);

        assertEquals(1, rows.size());
        assertEquals("result-a", rows.get(0).resultId());
        assertEquals(28, rows.get(0).byteSize());
        assertEquals("coordinator", rows.get(0).executorRoleId());
        assertFalse(rows.get(0).toString().contains("private complete answer body"));
        var later = query.findResults(new SessionHistoryQuery.ResultFilter(7, "session-a"),
                new SessionHistoryQuery.Position(T0, "result-a"), 20);
        assertTrue(later.isEmpty());
    }

    private void insertSession(String id, long owner, long employee, String status, Instant created) {
        jdbc.update("INSERT INTO platform_agent_session(session_id,user_id,employee_id,status,definition_version_id,created_at,last_active_at) "
                        + "VALUES(?,?,?,?,?,?,?)", id, owner, employee, status, 101L,
                Timestamp.from(created), Timestamp.from(created.plusSeconds(5)));
    }

    private void insertRun(String id, String sessionId, long owner, String state, Instant created,
                           long employeeId, long versionId) {
        jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,user_id,employee_id,definition_version_id,state,created_at) "
                        + "VALUES(?,?,?,?,?,?,?)", id, sessionId, owner, employeeId, versionId, state,
                Timestamp.from(created));
    }

    private void insertResult(String id, String runId, String visibility, String body, Instant created, String roleId) {
        byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO platform_agent_result(result_id,run_id,kind,media_type,body,body_sha256,byte_size,visibility,"
                        + "created_at,executor_role_id,employee_id,definition_version_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id, runId, "ROOT_FINAL", "text/plain", body, "a".repeat(64), bodyBytes.length, visibility,
                Timestamp.from(created), roleId, 11L, 101L);
    }
}
