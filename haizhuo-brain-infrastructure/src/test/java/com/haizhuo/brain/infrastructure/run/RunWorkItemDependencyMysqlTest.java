package com.haizhuo.brain.infrastructure.run;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.transactional;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.infrastructure.session.JdbcSessionEventProjector;
import com.haizhuo.brain.platform.channel.ChannelReplyEnqueuer;
import com.haizhuo.brain.platform.run.AgentDelegationResult;
import com.haizhuo.brain.platform.run.ExecutionClaim;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider.AcceptedDelegation;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider.DelegationCall;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider.ReviewDecision;
import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/** Real migration schema and transactions; child execution remains owned by AgentScope. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunWorkItemDependencyMysqlTest {
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withCommand("mysqld", "--log-bin-trust-function-creators=1");
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private JdbcRunExecutionStore store;
    private ExecutionClaim claim;
    private long versionId;

    @BeforeAll
    void startDatabase() {
        mysql.start();
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        assertTrue(Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate().success);
        jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO platform_user(id,mobile_normalized,status,auth_version,must_change_password,"
                + "created_at,updated_at) VALUES(91001,'+15550091001','ACTIVE',1,FALSE,NOW(3),NOW(3))");
        versionId = jdbc.queryForObject("SELECT current_published_version_id FROM digital_employee WHERE id=2", Long.class);
        store = newStore();
    }

    @AfterAll
    void stopDatabase() {
        mysql.stop();
    }

    @BeforeEach
    void createParentRun() {
        String sessionId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_agent_session(session_id,user_id,employee_id,status,created_at,"
                        + "last_active_at,definition_version_id,legacy_runtime) VALUES(?,91001,2,'ACTIVE',NOW(3),NOW(3),?,FALSE)",
                sessionId, versionId);
        jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,user_id,employee_id,definition_version_id,"
                        + "client_request_id,input_digest,state,runtime_profile,created_at) "
                        + "VALUES(?,?,91001,2,?,?,?,'QUEUED','TEAM_READONLY',NOW(3))",
                runId, sessionId, versionId, runId, "a".repeat(64));
        jdbc.update("INSERT INTO platform_agent_run_spec(run_id,definition_version_id,definition_bundle_id,"
                        + "definition_bundle_hash,model_visible_tool_names_json,tool_view_hash,effective_capability_hash,"
                        + "created_at) VALUES(?,?,1,?,'[]',?,?,NOW(3))",
                runId, versionId, "a".repeat(64), "b".repeat(64), "c".repeat(64));
        claim = store.claimNext("mysql-work-item-test", Duration.ofMinutes(5)).orElseThrow();
        assertEquals(runId, claim.run().id().value());
    }

    @Test
    void identicalSpawnAndFollowUpReuseReceiptsWithoutSpendingAnotherInvocation() throws Exception {
        var spawn = spawn("spawn-1", "researcher", payload("research", List.of()));
        var first = accept(spawn);
        var replay = accept(spawn);
        assertEquals(first.reservation().invocationId(), replay.reservation().invocationId());
        assertEquals(first.normalizedPayload(), replay.normalizedPayload());
        assertEquals(1, count("platform_run_delegation_invocation"));
        assertThrows(IllegalStateException.class, () -> accept(spawn("spawn-1", "other-role", spawn.payload())));
        assertThrows(IllegalStateException.class, () -> accept(spawn("spawn-1", "researcher", payload("changed", List.of()))));

        assertTrue(first.reservation().activate());
        assertFalse(replay.reservation().activate(), "one accepted receipt cannot launch the native child twice");
        replay.reservation().close();
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM platform_run_delegation_invocation WHERE invocation_id=?",
                String.class, first.reservation().invocationId()));
        store.recordDelegationResult(claim, result("researcher", "first result")).orElseThrow();
        var exact = store.readResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                first.workItemRef(), 1).orElseThrow();
        assertTrue(store.reviewResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                first.workItemRef(), 1, exact.resultId(), exact.bodySha256(), exact.contractVersion(),
                ReviewDecision.REQUEST_REVISION, "Please revise the conclusion"));
        var followUp = new DelegationCall("send-1", DelegationAcceptanceProvider.Operation.FOLLOW_UP,
                null, null, null, null, null, null, first.workItemRef(), payload("revision", List.of()));
        var revision = accept(followUp);
        var revisionReplay = accept(followUp);
        assertEquals(2, revision.assignmentRevision());
        assertEquals(revision.reservation().invocationId(), revisionReplay.reservation().invocationId());
        assertEquals(revision.normalizedPayload(), revisionReplay.normalizedPayload());
        assertEquals(2, count("platform_run_delegation_invocation"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_work_item_revision WHERE work_item_ref=?",
                Integer.class, first.workItemRef()));
    }

    @Test
    void concurrentAcceptancesCannotOverspendTheFrozenRunBudget() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var outcomes = List.of("researcher", "reviewer").stream().map(role -> pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    newStore().acceptBatch(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                            1, 1, List.of(spawn("spawn-" + role, role, payload(role, List.of()))));
                    return true;
                } catch (IllegalStateException budgetExhausted) {
                    return false;
                }
            })).toList();
            start.countDown();
            int successful = 0;
            for (var outcome : outcomes) if (outcome.get(20, TimeUnit.SECONDS)) successful++;
            assertEquals(1, successful);
            assertEquals(1, count("platform_run_work_item"));
            assertEquals(1, count("platform_run_delegation_invocation"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentDistinctRequestsReceiveContiguousEventsAndCursors() throws Exception {
        var receipts = parallelAccepts(List.of(
                spawn("spawn-parallel-a", "researcher", payload("research", List.of())),
                spawn("spawn-parallel-b", "reviewer", payload("review", List.of()))));
        assertNotEquals(receipts.get(0).reservation().invocationId(), receipts.get(1).reservation().invocationId());
        assertEquals(2, count("platform_run_delegation_invocation"));
        assertEquals(List.of(1, 2, 3), jdbc.query("SELECT sequence_no FROM platform_agent_run_event "
                        + "WHERE run_id=? ORDER BY sequence_no", (rs, row) -> rs.getInt(1), claim.run().id().value()));
        assertEquals(List.of(1L, 2L, 3L), jdbc.query("SELECT session_cursor FROM platform_agent_session_event "
                        + "WHERE run_id=? ORDER BY session_cursor", (rs, row) -> rs.getLong(1), claim.run().id().value()));
    }

    @Test
    void concurrentIdenticalRequestsReuseOneReceiptAndOneAcceptanceEvent() throws Exception {
        var call = spawn("spawn-parallel-replay", "researcher", payload("research", List.of()));
        var receipts = parallelAccepts(List.of(call, call));
        assertEquals(receipts.get(0).reservation().invocationId(), receipts.get(1).reservation().invocationId());
        assertEquals(receipts.get(0).normalizedPayload(), receipts.get(1).normalizedPayload());
        assertEquals(1, count("platform_run_delegation_invocation"));
        assertEquals(2, count("platform_agent_run_event"));
        assertEquals(2, count("platform_agent_session_event"));
    }

    @Test
    void cancellingRunRejectsLateChildResultWithoutChangingFactsOrProjection() throws Exception {
        var accepted = accept(spawn("spawn-cancel", "researcher", payload("research", List.of())));
        assertTrue(accepted.reservation().activate());
        jdbc.update("UPDATE platform_agent_run SET state='CANCELLING' WHERE run_id=?", claim.run().id().value());
        int eventsBefore = count("platform_agent_run_event");
        assertTrue(store.recordDelegationResult(claim, result("researcher", "late result")).isEmpty());
        assertEquals(0, count("platform_agent_result"));
        assertEquals(eventsBefore, count("platform_agent_run_event"));
        assertEquals(eventsBefore, count("platform_agent_session_event"));
        assertTrue(store.cancelled(claim, "Cancelled after child stopped"));
    }

    @Test
    void projectionFailureRollsBackWorkItemBudgetAndCursorTogether() throws Exception {
        long cursor = jdbc.queryForObject("SELECT next_event_cursor FROM platform_agent_session WHERE session_id=?",
                Long.class, claim.run().sessionId().value());
        createProjectionFailure();
        try {
            assertThrows(DataAccessException.class,
                    () -> accept(spawn("spawn-rollback", "researcher", payload("research", List.of()))));
        } finally {
            jdbc.execute("DROP TRIGGER work_item_projection_failure");
        }
        assertEquals(0, count("platform_run_work_item"));
        assertEquals(0, count("platform_run_delegation_invocation"));
        assertEquals(1, count("platform_agent_run_event"));
        assertEquals(1, count("platform_agent_session_event"));
        assertEquals(cursor, jdbc.queryForObject("SELECT next_event_cursor FROM platform_agent_session WHERE session_id=?",
                Long.class, claim.run().sessionId().value()));
    }

    @Test
    void completedChildWithUncertainResultWriteRequiresRecoveryInsteadOfReplay() throws Exception {
        var accepted = accept(spawn("spawn-write-failure", "researcher", payload("research", List.of())));
        assertTrue(accepted.reservation().activate());
        createProjectionFailure();
        try {
            assertThrows(DataAccessException.class, () -> store.recordDelegationResult(claim, result("researcher", "complete result")));
        } finally {
            jdbc.execute("DROP TRIGGER work_item_projection_failure");
        }
        assertEquals(0, count("platform_agent_result"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM platform_run_delegation_invocation WHERE invocation_id=?",
                String.class, accepted.reservation().invocationId()));
        assertTrue(store.markRecoveryRequired(claim, "CHILD_RESULT_PERSISTENCE_FAILED", "Child result needs verification"));
        assertEquals("RECOVERY_REQUIRED", jdbc.queryForObject("SELECT state FROM platform_agent_run WHERE run_id=?",
                String.class, claim.run().id().value()));
        assertTrue(store.recordDelegationResult(claim, result("researcher", "late complete result")).isEmpty());
        assertThrows(IllegalStateException.class, () -> accept(spawn("spawn-again", "researcher", payload("research", List.of()))));
        assertTrue(store.claimNext("another-worker", Duration.ofMinutes(5)).isEmpty());
    }

    private JdbcRunExecutionStore newStore() {
        return transactional(new JdbcRunExecutionStore(jdbc, new JdbcRunEventAppender(jdbc,
                new JdbcSessionEventProjector(jdbc)), new JdbcAgentResultRepository(jdbc), ChannelReplyEnqueuer.NOOP), jdbc);
    }

    private List<AcceptedDelegation> parallelAccepts(List<DelegationCall> calls) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            var outcomes = calls.stream().map(call -> pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return newStore().acceptBatch(claim.run().id(), claim.attempt().attemptId(),
                        claim.attempt().fenceToken(), 2, 2, List.of(call)).get(0);
            })).toList();
            start.countDown();
            var receipts = new java.util.ArrayList<AcceptedDelegation>();
            for (var outcome : outcomes) receipts.add(outcome.get(20, TimeUnit.SECONDS));
            return List.copyOf(receipts);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private AcceptedDelegation accept(DelegationCall call) {
        return store.acceptBatch(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                4, 2, List.of(call)).get(0);
    }

    private DelegationCall spawn(String toolId, String role, String payload) {
        return new DelegationCall(toolId, DelegationAcceptanceProvider.Operation.SPAWN, role,
                DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT, 2L, versionId, "a".repeat(64), null, null, payload);
    }

    private String payload(String objective, List<Map<String, Object>> dependencies) throws Exception {
        return json.writeValueAsString(Map.of("objective", objective, "required", true,
                "deliverable", Map.of("mediaType", "text/plain", "contractVersion", "research-v1", "requiredFields", List.of()),
                "inputRefs", List.of(), "dependsOn", dependencies));
    }

    private AgentDelegationResult result(String role, String body) {
        var descriptor = new AgentEventDescriptor(UUID.randomUUID().toString(), Instant.now().toString(), "AgentEndEvent",
                "parent/" + role, "reply-" + role, null, null, "sub-" + role, null,
                claim.run().sessionId().value(), AgentExecutionRole.CHILD,
                claim.attempt().attemptId(), claim.attempt().fenceToken());
        return new AgentDelegationResult(role, 2L, versionId, body, descriptor);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id=?", Integer.class, claim.run().id().value());
    }

    private void createProjectionFailure() {
        jdbc.execute("CREATE TRIGGER work_item_projection_failure BEFORE INSERT ON platform_agent_session_event "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Injected projection failure'");
    }
}
