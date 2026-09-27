package com.haizhuo.brain.infrastructure.run;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createRunAndToolTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.eventTypes;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.idempotencyKey;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertExecution;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRun;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRunSpec;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.runState;
import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.ExecutionClaim;
import com.haizhuo.brain.platform.run.ExecutionResumeReason;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.tool.ApprovalState;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ToolApproval;
import com.haizhuo.brain.platform.tool.ToolExecutionState;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
        store = new JdbcRunExecutionStore(jdbc);
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
