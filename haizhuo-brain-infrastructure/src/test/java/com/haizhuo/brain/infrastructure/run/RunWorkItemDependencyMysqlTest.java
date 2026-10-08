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
import com.haizhuo.brain.runtime.api.team.TeamExecutionMember;
import com.haizhuo.brain.runtime.api.team.TeamExecutionPersistence;
import com.haizhuo.brain.runtime.api.team.TeamExecutionSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    void sameRoleInstancesCommitExactFullResultsAndKeepFollowUpBoundToOneWorkItem() throws Exception {
        var firstCall = general("same-role-a", "analysis A");
        var secondCall = general("same-role-b", "analysis B");
        var accepted = store.acceptBatch(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                4, 2, List.of(firstCall, secondCall));
        var first = accepted.get(0);
        var second = accepted.get(1);
        assertNotEquals(first.workItemRef(), second.workItemRef());
        assertTrue(first.reservation().activate());
        assertTrue(second.reservation().activate());
        var firstDone = completed(first, "native-a", "complete-A");
        var secondDone = completed(second, "native-b", "complete-B");
        String secondId = complete(secondDone).orElseThrow(); // Deliberately reverse completion order.
        String firstId = complete(firstDone).orElseThrow();
        assertNotEquals(firstId, secondId);
        assertEquals(firstId, complete(firstDone).orElseThrow(), "identical completion is immutable and idempotent");
        assertThrows(IllegalStateException.class, () -> complete(completed(first, "native-a", "changed-A")));
        assertThrows(SecurityException.class, () -> complete(completed(first, "native-b", "complete-A")));
        var firstResult = store.readResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                first.workItemRef(), 1).orElseThrow();
        var secondResult = store.readResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                second.workItemRef(), 1).orElseThrow();
        assertEquals("complete-A", firstResult.body());
        assertEquals("complete-B", secondResult.body());
        assertTrue(store.reviewResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                first.workItemRef(), 1, firstId, firstResult.bodySha256(), firstResult.contractVersion(),
                ReviewDecision.REQUEST_REVISION, "Revise A only"));
        String wrongHash = "f".repeat(64);
        assertThrows(SecurityException.class, () -> accept(new DelegationCall("send-wrong-ref",
                DelegationAcceptanceProvider.Operation.FOLLOW_UP, null, null, null, null, null, null,
                first.workItemRef(), payload("analysis A revision", List.of(), List.of(),
                        Map.of("resultId", firstId, "contentHash", wrongHash)))));
        assertEquals(1, jdbc.queryForObject("SELECT latest_assignment_revision FROM platform_run_work_item "
                + "WHERE work_item_ref=?", Integer.class, first.workItemRef()));
        var followUp = new DelegationCall("send-only-a", DelegationAcceptanceProvider.Operation.FOLLOW_UP,
                null, null, null, null, null, null, first.workItemRef(),
                payload("analysis A revision", List.of(), List.of(),
                        Map.of("resultId", firstId, "contentHash", firstResult.bodySha256())));
        var revised = accept(followUp);
        assertEquals(first.workItemRef(), revised.workItemRef());
        assertEquals(2, revised.assignmentRevision());
        assertTrue(revised.reservation().activate());
        complete(completed(revised, "native-a", "complete-A2")).orElseThrow();
        assertEquals(3, count("platform_agent_result"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_result WHERE run_id=? AND kind='ROOT_FINAL'",
                Integer.class, claim.run().id().value()));
        assertEquals(count("platform_agent_run_event"), count("platform_agent_session_event"));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT state FROM platform_agent_run WHERE run_id=?",
                String.class, claim.run().id().value()));
    }

    @Test
    void exactInvocationCompletionRollsBackBodySessionAndProjectionOnDatabaseFailure() throws Exception {
        var accepted = accept(general("exact-write-failure", "analysis"));
        assertTrue(accepted.reservation().activate());
        createProjectionFailure();
        try {
            assertThrows(DataAccessException.class, () -> complete(completed(accepted, "native-exact", "full body")));
        } finally {
            jdbc.execute("DROP TRIGGER work_item_projection_failure");
        }
        assertEquals(0, count("platform_agent_result"));
        assertNull(jdbc.queryForObject("SELECT native_session_id FROM platform_run_delegation_invocation WHERE invocation_id=?",
                String.class, accepted.reservation().invocationId()));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM platform_run_delegation_invocation WHERE invocation_id=?",
                String.class, accepted.reservation().invocationId()));
    }

    @Test
    void rootMaterialIsFrozenAndMadeAvailableToReviewWithExactSameRunReference() throws Exception {
        String proposal = "# P1 proposal\nKeep the system read-only until risks are reviewed.";
        var planning = accept(spawn("spawn-p1-material", "planner", payload("draft P1", List.of(),
                List.of(Map.of("name", "proposal-p1", "mediaType", "text/markdown", "body", proposal)), null)));
        var material = jdbc.queryForMap("SELECT result_id,body_sha256,body,visibility,check_metadata_json "
                        + "FROM platform_agent_result WHERE run_id=? AND kind='RUN_MATERIAL'",
                claim.run().id().value());
        String materialId = (String) material.get("result_id");
        String materialHash = (String) material.get("body_sha256");
        assertEquals(proposal, material.get("body"));
        assertEquals("INTERNAL", material.get("visibility"));
        var materialMetadata = json.readTree((String) material.get("check_metadata_json"));
        assertEquals("ROOT_SUPPLIED", materialMetadata.path("source").asText());
        assertEquals("INTERNAL", materialMetadata.path("visibility").asText());
        var plannerInput = json.readTree(planning.normalizedPayload());
        assertEquals(proposal, plannerInput.path("materials").get(0).path("body").asText());

        var reviewCall = spawn("spawn-p1-review", "risk-reviewer", payload("review P1", List.of(
                Map.of("condition", "MATERIAL_READY", "kind", "RUN_MATERIAL", "id", materialId,
                        "contentHash", materialHash, "required", true)), List.of(),
                Map.of("resultId", materialId, "contentHash", materialHash), "application/json"));
        var review = accept(reviewCall);
        var reviewInput = json.readTree(review.normalizedPayload());
        assertEquals(proposal, reviewInput.path("materials").get(0).path("body").asText());
        assertEquals(materialId, reviewInput.path("reviewedResultRef").path("resultId").asText());
        assertEquals("RUN_MATERIAL", reviewInput.path("inputRefs").get(0).path("kind").asText());

        assertTrue(review.reservation().activate());
        String exactBody = json.writeValueAsString(Map.of("reviewedResultRef",
                Map.of("resultId", materialId, "contentHash", materialHash), "decision", "revise"));
        String exactResultId = complete(completed(review, "native-p1-review", exactBody)).orElseThrow();
        var exactResult = store.readResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                review.workItemRef(), 1).orElseThrow();
        assertEquals("VALID", jdbc.queryForObject("SELECT format_status FROM platform_run_work_item_revision "
                + "WHERE work_item_ref=? AND assignment_revision=1", String.class, review.workItemRef()));
        assertTrue(store.reviewResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                review.workItemRef(), 1, exactResultId, exactResult.bodySha256(), exactResult.contractVersion(),
                ReviewDecision.REQUEST_REVISION, "One risk needs another pass"));

        var mismatch = accept(spawn("spawn-p1-review-mismatch", "risk-reviewer-2", payload("review P1 again",
                List.of(), List.of(), Map.of("resultId", materialId, "contentHash", materialHash), "application/json")));
        assertTrue(mismatch.reservation().activate());
        String wrongBody = json.writeValueAsString(Map.of("reviewedResultRef",
                Map.of("resultId", materialId, "contentHash", "e".repeat(64)), "decision", "accept"));
        String mismatchId = complete(completed(mismatch, "native-p1-review-2", wrongBody)).orElseThrow();
        var mismatchResult = store.readResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                mismatch.workItemRef(), 1).orElseThrow();
        assertEquals("INVALID", jdbc.queryForObject("SELECT format_status FROM platform_run_work_item_revision "
                + "WHERE work_item_ref=? AND assignment_revision=1", String.class, mismatch.workItemRef()));
        assertFalse(store.reviewResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                mismatch.workItemRef(), 1, mismatchId, mismatchResult.bodySha256(), mismatchResult.contractVersion(),
                ReviewDecision.ACCEPT, "Must match the exact reviewed material"));
    }

    @Test
    void deliveryAcceptedDependencyCarriesTheAcceptedFullExpertResult() throws Exception {
        var upstream = accept(spawn("spawn-upstream-result", "researcher", payload("research competitor", List.of())));
        assertTrue(upstream.reservation().activate());
        String upstreamId = complete(completed(upstream, "native-upstream", "Full, uncropped research result"))
                .orElseThrow();
        var upstreamResult = store.readResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                upstream.workItemRef(), 1).orElseThrow();
        assertTrue(store.reviewResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                upstream.workItemRef(), 1, upstreamId, upstreamResult.bodySha256(), upstreamResult.contractVersion(),
                ReviewDecision.ACCEPT, "Accepted"));

        var dependent = accept(spawn("spawn-dependent-review", "reviewer", payload("review research", List.of(
                Map.of("condition", "DELIVERY_ACCEPTED", "workItemRef", upstream.workItemRef(),
                        "assignmentRevision", 1, "resultId", upstreamId,
                        "sha256", upstreamResult.bodySha256(), "required", true)))));
        var normalized = json.readTree(dependent.normalizedPayload());
        assertTrue(normalized.path("materials").toString().contains("Full, uncropped research result"));
        assertEquals(upstreamId, normalized.path("inputRefs").get(0).path("id").asText());
        assertEquals("DELEGATION_RESULT", normalized.path("inputRefs").get(0).path("kind").asText());
    }

    @Test
    void teamPersistenceFencesMembersBudgetsAndCompletionAgainstTheParentRun() {
        TeamFixture fixture = createTeamFixture();
        var persistence = fixture.persistence();
        var execution = fixture.execution();
        persistence.start(execution);
        assertEquals("RUNNING", jdbc.queryForObject("SELECT state FROM platform_run_team_execution "
                + "WHERE team_execution_id=?", String.class, execution.teamExecutionId()));
        assertTrue(persistence.recordMemberEvent(execution, "worker-a", TeamExecutionPersistence.MemberEvent.STARTED,
                1, Instant.now()));
        persistence.reserveAction(execution, TeamExecutionPersistence.Action.TASK_CREATED, 2, 6);
        assertThrows(IllegalStateException.class, () -> persistence.reserveAction(execution,
                TeamExecutionPersistence.Action.TASK_CREATED, 5, 6));
        persistence.reserveAction(execution, TeamExecutionPersistence.Action.MESSAGE_RECIPIENT, 20, 20);
        assertThrows(IllegalStateException.class, () -> persistence.reserveAction(execution,
                TeamExecutionPersistence.Action.MESSAGE_RECIPIENT, 1, 20));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_team_action_reservation "
                + "WHERE team_execution_id=?", Integer.class, execution.teamExecutionId()));

        assertTrue(persistence.complete(execution, "a".repeat(64)));
        assertFalse(persistence.complete(execution, "b".repeat(64)), "completion is one-way");
        assertThrows(IllegalStateException.class, () -> persistence.recordMemberEvent(execution, "worker-a",
                TeamExecutionPersistence.MemberEvent.ENDED, 1, Instant.now()),
                "late member lifecycle events cannot follow the completion barrier");
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT state FROM platform_run_team_execution "
                + "WHERE team_execution_id=?", String.class, execution.teamExecutionId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_team_member_binding "
                + "WHERE team_execution_id=?", Integer.class, execution.teamExecutionId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_event "
                + "WHERE run_id=? AND event_type='INTERNAL_TEAM_MEMBER_STARTED'", Integer.class,
                execution.runId().value()));
    }

    @Test
    void teamRecoveryAndFailedRosterInsertAreAtomicAndKeepTheOldGenerationFenced() {
        TeamFixture recoveryFixture = createTeamFixture();
        var recoveryPersistence = recoveryFixture.persistence();
        var recoveryExecution = recoveryFixture.execution();
        recoveryPersistence.start(recoveryExecution);
        assertTrue(recoveryPersistence.requireRecovery(recoveryExecution, "TEAM_TIMEOUT"));
        assertEquals("RECOVERY_REQUIRED", jdbc.queryForObject("SELECT state FROM platform_run_team_execution "
                + "WHERE team_execution_id=?", String.class, recoveryExecution.teamExecutionId()));
        assertEquals("RECOVERY_REQUIRED", jdbc.queryForObject("SELECT state FROM platform_agent_run "
                + "WHERE run_id=?", String.class, recoveryExecution.runId().value()));
        assertFalse(recoveryPersistence.requireRecovery(recoveryExecution, "TEAM_TIMEOUT"));
        assertThrows(IllegalStateException.class, () -> recoveryPersistence.reserveAction(recoveryExecution,
                TeamExecutionPersistence.Action.TASK_CREATED, 1, 6));

        TeamFixture rollbackFixture = createTeamFixture();
        jdbc.execute("CREATE TRIGGER team_binding_failure BEFORE INSERT ON platform_run_team_member_binding "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Injected Team binding failure'");
        try {
            assertThrows(DataAccessException.class,
                    () -> rollbackFixture.persistence().start(rollbackFixture.execution()));
        } finally {
            jdbc.execute("DROP TRIGGER team_binding_failure");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_team_execution "
                + "WHERE team_execution_id=?", Integer.class, rollbackFixture.execution().teamExecutionId()));
    }

    private DelegationCall general(String toolId, String objective) throws Exception {
        return new DelegationCall(toolId, DelegationAcceptanceProvider.Operation.SPAWN, "general-purpose",
                DelegationAcceptanceProvider.SourceKind.BUILTIN_GENERAL_PURPOSE, null, null, null,
                null, null, payload(objective, List.of()));
    }

    private DelegationAcceptanceProvider.CompletedInvocation completed(AcceptedDelegation accepted, String session, String body) {
        var descriptor = new AgentEventDescriptor(UUID.randomUUID().toString(), Instant.now().toString(), "AgentResultEvent",
                null, "reply-" + session, null, null, session, null, claim.run().sessionId().value(),
                AgentExecutionRole.CHILD, claim.attempt().attemptId(), claim.attempt().fenceToken());
        return new DelegationAcceptanceProvider.CompletedInvocation(accepted.reservation().invocationId(),
                accepted.roleId(), accepted.normalizedPayload(), body, descriptor);
    }

    private java.util.Optional<String> complete(DelegationAcceptanceProvider.CompletedInvocation completed) {
        return store.completeInvocation(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(), completed);
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
                null, null, null, null, null, null, first.workItemRef(),
                payload("revision", List.of(), List.of(),
                        Map.of("resultId", exact.resultId(), "contentHash", exact.bodySha256())));
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
                    () -> accept(spawn("spawn-rollback", "researcher", payload("research", List.of(),
                            List.of(Map.of("name", "rollback-material", "mediaType", "text/plain",
                                    "body", "must roll back with projection")), null))));
        } finally {
            jdbc.execute("DROP TRIGGER work_item_projection_failure");
        }
        assertEquals(0, count("platform_run_work_item"));
        assertEquals(0, count("platform_run_delegation_invocation"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_result "
                + "WHERE run_id=? AND kind='RUN_MATERIAL'", Integer.class, claim.run().id().value()));
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

    private TeamFixture createTeamFixture() {
        String sessionId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_agent_session(session_id,user_id,employee_id,status,created_at,"
                        + "last_active_at,definition_version_id,legacy_runtime) VALUES(?,91001,2,'ACTIVE',NOW(3),NOW(3),?,FALSE)",
                sessionId, versionId);
        jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,user_id,employee_id,definition_version_id,"
                        + "client_request_id,input_digest,state,runtime_profile,created_at) "
                        + "VALUES(?,?,91001,2,?,?,?,'QUEUED','TEAM_AUTONOMOUS_READONLY',NOW(3))",
                runId, sessionId, versionId, runId, "d".repeat(64));
        jdbc.update("INSERT INTO platform_agent_run_spec(run_id,definition_version_id,definition_bundle_id,"
                        + "definition_bundle_hash,model_visible_tool_names_json,tool_view_hash,effective_capability_hash,"
                        + "created_at) VALUES(?,?,1,?,'[]',?,?,NOW(3))",
                runId, versionId, "d".repeat(64), "e".repeat(64), "f".repeat(64));
        ExecutionClaim teamClaim = newStore().claimNext("mysql-team-persistence-test", Duration.ofMinutes(5)).orElseThrow();
        assertEquals(runId, teamClaim.run().id().value());
        String teamExecutionId = "team-" + UUID.randomUUID();
        var execution = new TeamExecutionSnapshot(teamExecutionId, teamClaim.run().id(), teamClaim.run().userId(),
                teamClaim.run().sessionId(), teamClaim.attempt().attemptId(), teamClaim.attempt().fenceToken(),
                "hb-" + UUID.randomUUID().toString().replace("-", ""), "team-ns-" + UUID.randomUUID().toString().replace("-", ""),
                versionId, List.of(
                new TeamExecutionMember("lead", 0, versionId, "team-lead-" + teamExecutionId,
                        TeamExecutionMember.Kind.LEAD),
                new TeamExecutionMember("worker-a", 2, versionId, "team-worker-" + teamExecutionId,
                        TeamExecutionMember.Kind.WORKER)), Instant.now());
        var persistence = transactional(new JdbcTeamExecutionPersistence(jdbc, new JdbcRunEventAppender(jdbc,
                new JdbcSessionEventProjector(jdbc))), jdbc);
        return new TeamFixture(persistence, execution);
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
        return payload(objective, dependencies, List.of(), null);
    }

    private String payload(String objective, List<Map<String, Object>> dependencies,
                           List<Map<String, Object>> inlineMaterials,
                           Map<String, String> reviewedResultRef) throws Exception {
        return payload(objective, dependencies, inlineMaterials, reviewedResultRef, "text/plain");
    }

    private String payload(String objective, List<Map<String, Object>> dependencies,
                           List<Map<String, Object>> inlineMaterials,
                           Map<String, String> reviewedResultRef, String mediaType) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("objective", objective);
        value.put("required", true);
        value.put("deliverable", Map.of("mediaType", mediaType, "contractVersion", "research-v1",
                "requiredFields", List.of()));
        value.put("inputRefs", List.of());
        value.put("dependsOn", dependencies);
        if (!inlineMaterials.isEmpty()) value.put("inlineMaterials", inlineMaterials);
        if (reviewedResultRef != null) value.put("reviewedResultRef", reviewedResultRef);
        return json.writeValueAsString(value);
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

    private record TeamFixture(JdbcTeamExecutionPersistence persistence, TeamExecutionSnapshot execution) { }
}
