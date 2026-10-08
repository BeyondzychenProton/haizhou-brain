package com.haizhuo.brain.infrastructure.run;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createRunAndToolTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createSessionAndGuidanceTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createSessionEventTable;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.eventTypes;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.idempotencyKey;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertExecution;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRun;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRunSpec;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertSession;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.runState;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.infrastructure.session.JdbcSessionEventProjector;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.ExecutionClaim;
import com.haizhuo.brain.platform.run.ExecutionResumeReason;
import com.haizhuo.brain.platform.run.AgentResult;
import com.haizhuo.brain.platform.run.AgentDelegationResult;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.run.RunCompletion;
import com.haizhuo.brain.platform.tool.ApprovalState;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ToolApproval;
import com.haizhuo.brain.platform.tool.ToolExecutionState;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;
import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcRunExecutionStore 的 durable 语义（spec §13/§15/§45.1）：claim+lease+fence、
 * suspend/complete/fail/cancel 落库、§45.1 一次性投递守卫、§15 租约回收与 §13.6 过期
 * worker  fencing。表结构为内联最小 DDL；原子性在生产由 @Transactional 保证。
 */
class JdbcRunExecutionStoreTest {

    private static final Duration TTL = Duration.ofSeconds(120);

    private JdbcTemplate jdbc;
    private JdbcRunExecutionStore store;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("runexec");
        createRunAndToolTables(jdbc);
        jdbc.execute("CREATE TABLE platform_run_delegation_invocation(invocation_id VARCHAR(64) PRIMARY KEY,"
                + "run_id VARCHAR(64) NOT NULL,attempt_id VARCHAR(64) NOT NULL,fence_token BIGINT NOT NULL,"
                + "role_id VARCHAR(64) NOT NULL,state VARCHAR(16) NOT NULL,started_at TIMESTAMP NOT NULL,"
                + "finished_at TIMESTAMP NULL,tool_use_id VARCHAR(128),work_item_ref VARCHAR(64),"
                + "assignment_revision INT,source_kind VARCHAR(32),employee_id BIGINT,definition_version_id BIGINT,"
                + "definition_hash CHAR(64),input_sha256 CHAR(64))");
        jdbc.execute("CREATE TABLE platform_run_work_item(work_item_ref VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64) NOT NULL,"
                + "role_id VARCHAR(64) NOT NULL,source_kind VARCHAR(32) NOT NULL,employee_id BIGINT,"
                + "definition_version_id BIGINT,definition_hash CHAR(64),required BOOLEAN NOT NULL,"
                + "latest_assignment_revision INT NOT NULL,state VARCHAR(24) NOT NULL,created_at TIMESTAMP NOT NULL,"
                + "updated_at TIMESTAMP NOT NULL,UNIQUE(run_id,role_id))");
        jdbc.execute("CREATE TABLE platform_run_work_item_revision(work_item_ref VARCHAR(64) NOT NULL,"
                + "assignment_revision INT NOT NULL,payload_json CLOB NOT NULL,payload_sha256 CHAR(64) NOT NULL,"
                + "objective CLOB NOT NULL,deliverable_media_type VARCHAR(64) NOT NULL,contract_version VARCHAR(64) NOT NULL,"
                + "required_fields_json CLOB NOT NULL,input_refs_json CLOB NOT NULL,dependencies_json CLOB NOT NULL,"
                + "result_id VARCHAR(64),result_sha256 CHAR(64),format_status VARCHAR(16) NOT NULL,"
                + "review_status VARCHAR(24) NOT NULL,reviewed_result_id VARCHAR(64),review_reason VARCHAR(2000),"
                + "created_at TIMESTAMP NOT NULL,reviewed_at TIMESTAMP NULL,PRIMARY KEY(work_item_ref,assignment_revision))");
        // 会话事件投影要与 run 事件同事务写入，因此需要会话表与投影表。
        createSessionAndGuidanceTables(jdbc);
        createSessionEventTable(jdbc);
        insertSession(jdbc, "session-1", 42L, 1L, "ACTIVE");
        insertSession(jdbc, "session-2", 42L, 1L, "ACTIVE");
        store = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.transactional(
                new JdbcRunExecutionStore(jdbc, new JdbcSessionEventProjector(jdbc)), jdbc);
    }

    @Test
    void claimCreatesAttemptWithFreshFenceAndStartsRun() {
        insertRun(jdbc, "run-1", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-1");

        ExecutionClaim claim = store.claimNext("worker-1", TTL).orElseThrow();
        assertEquals(ExecutionResumeReason.INITIAL_PROMPT, claim.resumeReason());
        assertEquals(1, claim.attempt().attemptNo());
        assertEquals(1, claim.attempt().fenceToken());
        assertEquals("RUNNING", runState(jdbc, "run-1"));
        assertEquals(List.of("RUN_STARTED"), eventTypes(jdbc, "run-1"));
        assertNotNull(jdbc.queryForObject("SELECT started_at FROM platform_agent_run WHERE run_id='run-1'", Timestamp.class));

        assertTrue(store.claimNext("worker-1", TTL).isEmpty(), "已 RUNNING 的 Run 不应被重复认领");
    }

    @Test
    void delegationBudgetIsFencedAndInvocationCountSurvivesAttemptChanges() {
        insertRun(jdbc, "run-budget", "session-1", "RUNNING");
        jdbc.update("UPDATE platform_agent_run SET runtime_profile='TEAM_READONLY' WHERE run_id='run-budget'");
        insertBudgetAttempt("attempt-budget-1", "run-budget", 1);
        insertBudgetAttempt("attempt-budget-2", "run-budget", 2);

        var first = store.reserve(new RunId("run-budget"), "attempt-budget-1", 1L,
                "researcher", 1, 1);
        assertFalse(first.invocationId().isBlank());
        first.close();
        first.close();
        assertEquals("FINISHED", jdbc.queryForObject("SELECT state FROM platform_run_delegation_invocation "
                + "WHERE invocation_id=?", String.class, first.invocationId()));
        assertThrows(IllegalStateException.class, () -> store.reserve(new RunId("run-budget"),
                "attempt-budget-2", 2L, "researcher", 1, 1),
                "attempt 重建不能重置 Run 总调用预算");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_delegation_invocation "
                + "WHERE run_id='run-budget'", Integer.class));

        jdbc.update("UPDATE platform_run_execution_attempt SET state='FAILED' WHERE attempt_id='attempt-budget-1'");
        assertThrows(IllegalStateException.class, () -> store.reserve(new RunId("run-budget"),
                "attempt-budget-1", 1L, "researcher", 5, 2), "旧 attempt/fence 不得启动子 Agent");
    }

    @Test
    void delegationBudgetEnforcesParallelAndSameRoleAndReleasesOnlyActiveSlot() {
        insertRun(jdbc, "run-budget-slots", "session-1", "RUNNING");
        jdbc.update("UPDATE platform_agent_run SET runtime_profile='TEAM_READONLY' WHERE run_id='run-budget-slots'");
        insertBudgetAttempt("attempt-budget-slots", "run-budget-slots", 1);

        var first = store.reserve(new RunId("run-budget-slots"), "attempt-budget-slots", 1L,
                "researcher", 4, 2);
        assertThrows(IllegalStateException.class, () -> store.reserve(new RunId("run-budget-slots"),
                "attempt-budget-slots", 1L, "researcher", 4, 2), "同一固定角色同一时间只能有一个活动调用");
        var second = store.reserve(new RunId("run-budget-slots"), "attempt-budget-slots", 1L,
                "planner", 4, 2);
        assertThrows(IllegalStateException.class, () -> store.reserve(new RunId("run-budget-slots"),
                "attempt-budget-slots", 1L, "reviewer", 4, 2), "活动并发数不能超过冻结策略");

        first.close();
        var third = store.reserve(new RunId("run-budget-slots"), "attempt-budget-slots", 1L,
                "researcher", 4, 2);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_delegation_invocation "
                + "WHERE run_id='run-budget-slots'", Integer.class), "释放并发槽不退还已消耗调用预算");
        second.close();
        third.close();
    }

    @Test
    void completedDelegationResultIsSavedWithItsInvocationAndInternalEventAtomically() {
        insertRun(jdbc, "run-result", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-result");
        jdbc.update("UPDATE platform_agent_run SET runtime_profile='TEAM_READONLY' WHERE run_id='run-result'");
        ExecutionClaim claim = store.claimNext("worker-result", TTL).orElseThrow();
        var call = new DelegationAcceptanceProvider.DelegationCall("tool-use-result",
                DelegationAcceptanceProvider.Operation.SPAWN, "researcher",
                DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT, 22L, 202L, "a".repeat(64),
                null, null, workItemPayload("查找事实", true, "text/plain", "research-v1", List.of(),
                        List.of(), List.of()));
        var accepted = store.acceptBatch(claim.run().id(), claim.attempt().attemptId(),
                claim.attempt().fenceToken(), 4, 2, List.of(call)).get(0);
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT state FROM platform_run_delegation_invocation "
                + "WHERE invocation_id=?", String.class, accepted.reservation().invocationId()));
        assertTrue(accepted.reservation().activate());
        AgentEventDescriptor descriptor = new AgentEventDescriptor("native-result-event", "2026-10-08T00:00:00Z",
                "AgentEndEvent", "session-1/researcher", "reply-1", null, null, "sub-child",
                null, "session-1", AgentExecutionRole.CHILD, claim.attempt().attemptId(), claim.attempt().fenceToken());

        String resultId = store.recordDelegationResult(claim, new AgentDelegationResult(
                "researcher", 22L, 202L, "完整的专家答复", descriptor)).orElseThrow();

        assertEquals("DELEGATION_FINAL", jdbc.queryForObject("SELECT kind FROM platform_agent_result "
                + "WHERE result_id=?", String.class, resultId));
        assertEquals(accepted.reservation().invocationId(), jdbc.queryForObject("SELECT invocation_id FROM platform_agent_result "
                + "WHERE result_id=?", String.class, resultId));
        assertEquals("researcher", jdbc.queryForObject("SELECT executor_role_id FROM platform_agent_result "
                + "WHERE result_id=?", String.class, resultId));
        assertEquals("完整的专家答复", jdbc.queryForObject("SELECT body FROM platform_agent_result "
                + "WHERE result_id=?", String.class, resultId));
        assertEquals("FINISHED", jdbc.queryForObject("SELECT state FROM platform_run_delegation_invocation "
                + "WHERE invocation_id=?", String.class, accepted.reservation().invocationId()));
        assertEquals(resultId, jdbc.queryForObject("SELECT result_id FROM platform_agent_run_event "
                + "WHERE run_id='run-result' AND event_type='WORK_ITEM_RESULT_READY'", String.class));
        assertEquals("INTERNAL", jdbc.queryForObject("SELECT visibility FROM platform_agent_session_event "
                + "WHERE run_id='run-result' AND event_type='WORK_ITEM_RESULT_READY'", String.class));

        var exact = store.readResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                accepted.workItemRef(), accepted.assignmentRevision()).orElseThrow();
        assertEquals(resultId, exact.resultId());
        assertEquals("完整的专家答复", exact.body());
        assertEquals("research-v1", exact.contractVersion());
        assertEquals("VALID", exact.formatStatus());
        assertFalse(store.reviewResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                accepted.workItemRef(), accepted.assignmentRevision(), resultId, "b".repeat(64),
                exact.contractVersion(), DelegationAcceptanceProvider.ReviewDecision.ACCEPT, null));
        assertTrue(store.reviewResult(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                accepted.workItemRef(), accepted.assignmentRevision(), resultId, exact.bodySha256(),
                exact.contractVersion(), DelegationAcceptanceProvider.ReviewDecision.ACCEPT, null));
        assertTrue(new JdbcDelegationWorkItemRepository(jdbc, new JdbcRunEventAppender(jdbc,
                new JdbcSessionEventProjector(jdbc)), new JdbcAgentResultRepository(jdbc))
                .requiredWorkItemsAccepted(claim.run().id()));
    }

    @Test
    void requiredWorkItemWithoutExactAcceptanceBlocksRootCompletion() {
        insertRun(jdbc, "run-work-item-gate", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-work-item-gate");
        jdbc.update("UPDATE platform_agent_run SET runtime_profile='TEAM_READONLY' WHERE run_id='run-work-item-gate'");
        ExecutionClaim claim = store.claimNext("worker-work-item-gate", TTL).orElseThrow();
        var call = new DelegationAcceptanceProvider.DelegationCall("tool-use-gate",
                DelegationAcceptanceProvider.Operation.SPAWN, "researcher",
                DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT, 22L, 202L, "a".repeat(64),
                null, null, workItemPayload("查找事实", true, "text/plain", "research-v1", List.of(),
                        List.of(), List.of()));
        store.acceptBatch(claim.run().id(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                4, 2, List.of(call));

        assertEquals(RunCompletion.Status.REJECTED,
                store.completeFinal(claim, new RunCompletion("总结", "text/markdown", List.of(), null)));
        assertEquals("FAILED", runState(jdbc, "run-work-item-gate"));
        assertEquals("WORK_ITEM_ACCEPTANCE_REQUIRED", jdbc.queryForObject(
                "SELECT failure_code FROM platform_agent_run WHERE run_id='run-work-item-gate'", String.class));
    }

    @Test
    void workItemRejectsMissingOrChangedInputResultReferencesBeforeAcceptance() {
        insertRun(jdbc, "run-work-item-refs", "session-1", "RUNNING");
        jdbc.update("UPDATE platform_agent_run SET runtime_profile='TEAM_READONLY' WHERE run_id='run-work-item-refs'");
        insertBudgetAttempt("attempt-work-item-refs", "run-work-item-refs", 1);
        var call = new DelegationAcceptanceProvider.DelegationCall("tool-use-ref",
                DelegationAcceptanceProvider.Operation.SPAWN, "researcher",
                DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT, 22L, 202L, "a".repeat(64),
                null, null, workItemPayload("汇总引用结果", true, "text/plain", "research-v1", List.of(),
                        List.of(Map.of("resultId", "missing-result", "sha256", "b".repeat(64))), List.of()));

        assertThrows(SecurityException.class, () -> store.acceptBatch(new RunId("run-work-item-refs"),
                "attempt-work-item-refs", 1L, 4, 2, List.of(call)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_work_item WHERE run_id=?",
                Integer.class, "run-work-item-refs"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_delegation_invocation WHERE run_id=?",
                Integer.class, "run-work-item-refs"));
    }

    private static String workItemPayload(String objective, boolean required, String mediaType,
                                          String contractVersion, List<String> requiredFields,
                                          List<Map<String, String>> inputRefs,
                                          List<Map<String, Object>> dependencies) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "objective", objective,
                    "required", required,
                    "deliverable", java.util.Map.of("mediaType", mediaType,
                            "contractVersion", contractVersion, "requiredFields", requiredFields),
                    "inputRefs", inputRefs,
                    "dependsOn", dependencies));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private void insertBudgetAttempt(String attemptId, String runId, long fenceToken) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO platform_run_execution_attempt(attempt_id,run_id,attempt_no,worker_id,lease_token,"
                        + "fence_token,lease_expires_at,heartbeat_at,state,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                attemptId, runId, fenceToken, "budget-test-worker", UUID.randomUUID().toString(), fenceToken,
                Timestamp.from(now.plusSeconds(120)), Timestamp.from(now), "RUNNING", Timestamp.from(now));
    }

    @Test
    void claimSkipsSessionWithActiveRunButServesOtherSessions() {
        insertRun(jdbc, "run-active", "session-1", "RUNNING");
        insertRun(jdbc, "run-blocked", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-blocked");
        insertRun(jdbc, "run-free", "session-2", "QUEUED");
        insertRunSpec(jdbc, "run-free");

        ExecutionClaim claim = store.claimNext("worker-1", TTL).orElseThrow();
        assertEquals("run-free", claim.run().id().value(), "同会话有活动 Run 时必须跳过，其他会话不受影响");
        assertTrue(store.claimNext("worker-1", TTL).isEmpty());
    }

    @Test
    void claimFailsFastWhenRunSpecMissing() {
        insertRun(jdbc, "run-1", "session-1", "QUEUED");
        assertThrows(IllegalStateException.class, () -> store.claimNext("worker-1", TTL),
                "缺少冻结 run spec 的 Run 必须快速失败（spec §7.6）");
    }

    @Test
    void heartbeatExtendsLeaseAndRejectsWrongToken() {
        insertRun(jdbc, "run-1", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-1");
        ExecutionClaim claim = store.claimNext("worker-1", TTL).orElseThrow();

        assertTrue(store.heartbeat(claim.attempt().attemptId(), claim.attempt().leaseToken(),
                claim.attempt().fenceToken(), TTL));
        Timestamp renewed = jdbc.queryForObject(
                "SELECT lease_expires_at FROM platform_run_execution_attempt WHERE attempt_id=?",
                Timestamp.class, claim.attempt().attemptId());
        assertTrue(renewed.toInstant().isAfter(claim.attempt().leaseExpiresAt().minusSeconds(1)),
                "心跳应延长租约");

        assertFalse(store.heartbeat(claim.attempt().attemptId(), "wrong-token",
                claim.attempt().fenceToken(), TTL), "错误 leaseToken 必须被拒绝");
    }

    @Test
    void suspendPersistsExecutionsApprovalsAndFlipsDeliveryGuard() {
        insertRun(jdbc, "run-1", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-1");
        // 已完成的工具结果，等待一次性投递（§45.1）
        insertExecution(jdbc, "exec-done", "run-1", "tu-0", "SUCCEEDED", "READY");

        ExecutionClaim claim = store.claimNext("worker-1", TTL).orElseThrow();
        assertEquals(ExecutionResumeReason.TOOL_RESULT_READY, claim.resumeReason(),
                "存在 READY 结果时恢复原因必须是 TOOL_RESULT_READY");

        Instant now = Instant.now();
        PlatformToolExecution plain = execution("exec-1", "run-1", "tu-1", ToolExecutionState.REQUESTED, now);
        PlatformToolExecution guarded = execution("exec-2", "run-1", "tu-2", ToolExecutionState.APPROVAL_REQUIRED, now);
        ToolApproval approval = new ToolApproval(UUID.randomUUID().toString(), "exec-2",
                new RunId("run-1"), new UserId(42), ApprovalState.PENDING, null, null, now, null);

        boolean suspended = store.suspendForTools(claim, List.of(plain, guarded), List.of(approval),
                RunState.WAITING_CONFIRMATION, List.of("exec-done"));
        assertTrue(suspended);

        assertEquals("WAITING_CONFIRMATION", runState(jdbc, "run-1"));
        assertEquals("SUSPENDED", jdbc.queryForObject(
                "SELECT state FROM platform_run_execution_attempt WHERE attempt_id=?", String.class, claim.attempt().attemptId()));
        assertEquals("REQUESTED", jdbc.queryForObject(
                "SELECT state FROM platform_tool_execution WHERE tool_execution_id='exec-1'", String.class));
        assertEquals("APPROVAL_REQUIRED", jdbc.queryForObject(
                "SELECT state FROM platform_tool_execution WHERE tool_execution_id='exec-2'", String.class));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT decision FROM platform_tool_approval WHERE tool_execution_id='exec-2'", String.class));
        assertEquals(List.of("RUN_STARTED", "TOOL_REQUESTED", "TOOL_REQUESTED", "TOOL_APPROVAL_REQUIRED"),
                eventTypes(jdbc, "run-1"));
        assertEquals("DELIVERED", jdbc.queryForObject(
                "SELECT result_delivery_state FROM platform_tool_execution WHERE tool_execution_id='exec-done'", String.class),
                "suspend 消费了恢复结果，必须翻转一次性投递守卫（§45.1）");

        assertFalse(store.suspendForTools(claim, List.of(plain), List.of(), RunState.WAITING_TOOL, List.of()),
                "fence 已失效（attempt 非 RUNNING），重复 suspend 必须被拒绝");
    }

    @Test
    void suspendIsIdempotentForDuplicateToolUseId() {
        insertRun(jdbc, "run-1", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-1");
        ExecutionClaim claim = store.claimNext("worker-1", TTL).orElseThrow();
        Instant now = Instant.now();
        PlatformToolExecution execution = execution("exec-1", "run-1", "tu-1", ToolExecutionState.REQUESTED, now);

        assertTrue(store.suspendForTools(claim, List.of(execution, execution), List.of(),
                RunState.WAITING_TOOL, List.of()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_tool_execution WHERE run_id='run-1' AND tool_use_id='tu-1'", Integer.class),
                "(run_id, tool_use_id) 唯一：重复 suspend 不得重复插入（§6.8）");
    }

    @Test
    void completeFlipsDeliveryGuardFinishesRunAndRejectsReplay() {
        insertRun(jdbc, "run-1", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-1");
        insertExecution(jdbc, "exec-done", "run-1", "tu-0", "SUCCEEDED", "READY");
        ExecutionClaim claim = store.claimNext("worker-1", TTL).orElseThrow();

        assertTrue(store.complete(claim, "最终答复", List.of("exec-done")));
        assertEquals("SUCCEEDED", runState(jdbc, "run-1"));
        assertEquals("DELIVERED", jdbc.queryForObject(
                "SELECT result_delivery_state FROM platform_tool_execution WHERE tool_execution_id='exec-done'", String.class));
        assertEquals(List.of("RUN_STARTED", "RUN_COMPLETED"), eventTypes(jdbc, "run-1"));
        assertNotNull(jdbc.queryForObject("SELECT finished_at FROM platform_agent_run WHERE run_id='run-1'", Timestamp.class));

        assertFalse(store.complete(claim, "重复完成", List.of()), "同一 claim 重复完成必须被 fence 拒绝");
    }

    @Test
    void finalResultCarriesTheFrozenDirectExpertIdentity() {
        insertRun(jdbc, "run-expert", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-expert");
        jdbc.update("INSERT INTO platform_agent_run_execution_target(run_id,execution_mode,role_id,employee_id,"
                        + "definition_version_id,created_at) VALUES('run-expert','DIRECT','researcher',2,20,?)",
                Timestamp.from(T0));

        ExecutionClaim claim = store.claimNext("worker-1", TTL).orElseThrow();
        assertEquals(RunCompletion.Status.COMMITTED, store.completeFinal(claim,
                new RunCompletion("研究结论", "text/markdown", List.of(), null)));

        AgentResult result = new JdbcAgentResultRepository(jdbc).findRoot(new RunId("run-expert"), new UserId(42))
                .orElseThrow();
        assertEquals("researcher", result.executorRoleId());
        assertEquals(2L, result.executorEmployeeId());
        assertEquals(20L, result.executorDefinitionVersionId());
    }

    @Test
    void failAndCancelledSettleTerminalStates() {
        insertRun(jdbc, "run-fail", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-fail");
        ExecutionClaim failing = store.claimNext("worker-1", TTL).orElseThrow();
        assertTrue(store.fail(failing, "AGENT_RUNTIME", "运行失败"));
        assertEquals("FAILED", runState(jdbc, "run-fail"));
        assertEquals("AGENT_RUNTIME", jdbc.queryForObject(
                "SELECT failure_code FROM platform_agent_run WHERE run_id='run-fail'", String.class));
        assertEquals(List.of("RUN_STARTED", "RUN_FAILED"), eventTypes(jdbc, "run-fail"));

        insertRun(jdbc, "run-cancel", "session-2", "QUEUED");
        insertRunSpec(jdbc, "run-cancel");
        ExecutionClaim cancelling = store.claimNext("worker-1", TTL).orElseThrow();
        jdbc.update("UPDATE platform_agent_run SET state='CANCELLING' WHERE run_id='run-cancel'");
        assertTrue(store.cancelled(cancelling, "已按请求取消"));
        assertEquals("CANCELLED", runState(jdbc, "run-cancel"));
        assertEquals(List.of("RUN_STARTED", "RUN_CANCELLED"), eventTypes(jdbc, "run-cancel"));
    }

    @Test
    void reclaimRequeuesExpiredRunAndSettlesCancellingRun() {
        insertRun(jdbc, "run-expire", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-expire");
        ExecutionClaim expired = store.claimNext("worker-1", TTL).orElseThrow();
        jdbc.update("UPDATE platform_run_execution_attempt SET lease_expires_at=? WHERE attempt_id=?",
                Timestamp.from(T0), expired.attempt().attemptId());

        insertRun(jdbc, "run-cancelling", "session-2", "QUEUED");
        insertRunSpec(jdbc, "run-cancelling");
        ExecutionClaim cancelling = store.claimNext("worker-1", TTL).orElseThrow();
        jdbc.update("UPDATE platform_agent_run SET state='CANCELLING' WHERE run_id='run-cancelling'");
        jdbc.update("UPDATE platform_run_execution_attempt SET lease_expires_at=? WHERE attempt_id=?",
                Timestamp.from(T0), cancelling.attempt().attemptId());

        assertEquals(2, store.reclaimExpiredLeases());
        assertEquals("LEASE_LOST", jdbc.queryForObject(
                "SELECT state FROM platform_run_execution_attempt WHERE attempt_id=?", String.class, expired.attempt().attemptId()));
        assertEquals("QUEUED", runState(jdbc, "run-expire"), "§15：租约过期的 Run 重新排队（at-least-once）");
        assertEquals(List.of("RUN_STARTED", "RUN_REQUEUED_AFTER_LEASE_TIMEOUT"), eventTypes(jdbc, "run-expire"));
        assertEquals("CANCELLED", runState(jdbc, "run-cancelling"), "取消中的 Run 不重新排队，直接落定 CANCELLED");
        assertEquals(List.of("RUN_STARTED", "RUN_CANCELLED"), eventTypes(jdbc, "run-cancelling"));
        assertEquals(0, store.reclaimExpiredLeases(), "没有 RUNNING 租约时不应重复回收");
    }

    @Test
    void nonLegacyExpiredLeaseRequiresRecoveryAndBlocksSessionTakeover() {
        insertRun(jdbc, "run-recovery", "session-1", "QUEUED");
        jdbc.update("UPDATE platform_agent_run SET runtime_profile='SINGLE_SKILLED' WHERE run_id='run-recovery'");
        insertRunSpec(jdbc, "run-recovery");
        ExecutionClaim uncertain = store.claimNext("worker-1", TTL).orElseThrow();
        assertEquals("run-recovery", uncertain.run().id().value());
        jdbc.update("UPDATE platform_run_execution_attempt SET lease_expires_at=? WHERE attempt_id=?",
                Timestamp.from(T0), uncertain.attempt().attemptId());

        insertRun(jdbc, "run-blocked", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-blocked");
        insertRun(jdbc, "run-free", "session-2", "QUEUED");
        insertRunSpec(jdbc, "run-free");

        assertEquals(1, store.reclaimExpiredLeases());
        assertEquals("RECOVERY_REQUIRED", runState(jdbc, "run-recovery"));
        assertEquals("RECOVERY_REQUIRED", jdbc.queryForObject(
                "SELECT state FROM platform_run_execution_attempt WHERE attempt_id=?", String.class,
                uncertain.attempt().attemptId()));
        assertEquals(List.of("RUN_STARTED", "RUN_RECOVERY_REQUIRED"), eventTypes(jdbc, "run-recovery"));

        ExecutionClaim free = store.claimNext("worker-2", TTL).orElseThrow();
        assertEquals("run-free", free.run().id().value(), "Recovery holds its Session slot while other Sessions keep working");
    }

    @Test
    void staleWorkerCannotOverwriteAfterReclaim() {
        insertRun(jdbc, "run-1", "session-1", "QUEUED");
        insertRunSpec(jdbc, "run-1");
        ExecutionClaim stale = store.claimNext("worker-1", TTL).orElseThrow();
        jdbc.update("UPDATE platform_run_execution_attempt SET lease_expires_at=? WHERE attempt_id=?",
                Timestamp.from(T0), stale.attempt().attemptId());
        assertEquals(1, store.reclaimExpiredLeases());

        ExecutionClaim fresh = store.claimNext("worker-2", TTL).orElseThrow();
        assertEquals(2, fresh.attempt().attemptNo(), "重新认领必须递增 attempt_no");
        assertEquals(2, fresh.attempt().fenceToken(), "fence token 必须单调递增（§13.6）");

        assertFalse(store.complete(stale, "过期 worker 的写入", List.of()),
                "I-11：租约被回收后，旧 worker 的写入必须被 fence 拒绝");
        assertEquals("RUNNING", runState(jdbc, "run-1"));
        assertFalse(store.heartbeat(stale.attempt().attemptId(), stale.attempt().leaseToken(),
                stale.attempt().fenceToken(), TTL), "LEASE_LOST 后旧 worker 心跳必须失败");

        assertTrue(store.complete(fresh, "新 worker 的结果", List.of()));
        assertEquals("SUCCEEDED", runState(jdbc, "run-1"));
    }

    private static PlatformToolExecution execution(String id, String runId, String toolUseId,
                                                   ToolExecutionState state, Instant now) {
        return new PlatformToolExecution(id, new RunId(runId), toolUseId, 101L, "meeting_room_search",
                "{\"roomId\":\"A-201\"}", "i".repeat(64), state, idempotencyKey(id),
                null, null, null, null, null, now, now);
    }
}
