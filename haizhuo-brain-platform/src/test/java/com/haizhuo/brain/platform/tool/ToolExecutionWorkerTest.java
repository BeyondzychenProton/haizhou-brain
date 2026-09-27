package com.haizhuo.brain.platform.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ToolExecutionWorker 的状态机（spec §36/§43.4）：DENIED 直接落终态、APPROVAL_REQUIRED
 * 停靠审批、ALLOWED 执行后 Tx-05 落定、RECONCILIATION_REQUIRED 不盲目重试、异常兜底
 * FAILED。网关用可编排的 fake，仓储用内存记录。
 */
class ToolExecutionWorkerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final CapabilityCatalogEntry CAPABILITY = new CapabilityCatalogEntry(101L,
            "meeting-room.reserve", CapabilityBinding.CapabilityType.TOOL, "1", "预订会议室", "预订",
            "meeting_room_reserve", "meeting-room-v1", "meeting_room.booking.create",
            Map.of("type", "object"), true, true);

    private PlatformToolExecution execution;
    private AgentRun run;
    private AtomicReference<ClaimedToolExecution> claim;
    private AtomicReference<PlatformToolExecution> completed;
    private AtomicReference<PlatformToolExecution> parked;
    private ToolPreparation preparation;
    private ToolExecutionResult executionResult;
    private RuntimeException gatewayError;
    private ToolExecutionWorker worker;

    @BeforeEach
    void setUp() {
        execution = new PlatformToolExecution("exec-1", new RunId("run-1"), "tu-1", 101L,
                "meeting_room_reserve", "{\"roomId\":\"A-201\"}", "i".repeat(64),
                ToolExecutionState.EXECUTING, "k".repeat(64), null, null, null, null, null, T0, T0);
        run = new AgentRun(new RunId("run-1"), new SessionId("session-1"), new UserId(42), 1L, 7L,
                "req-1", "d".repeat(64), RunState.WAITING_TOOL, T0, T0, null);
        claim = new AtomicReference<>(new ClaimedToolExecution(execution, run));
        completed = new AtomicReference<>();
        parked = new AtomicReference<>();
        preparation = null;
        executionResult = null;
        gatewayError = null;

        ToolExecutionRepository executions = new ToolExecutionRepository() {
            @Override public Optional<PlatformToolExecution> findById(String id) { return Optional.empty(); }
            @Override public Optional<PlatformToolExecution> findByRunAndToolUseId(RunId runId, String toolUseId) { return Optional.empty(); }
            @Override public List<PlatformToolExecution> findByRun(RunId runId) { return List.of(); }
            @Override public List<PlatformToolExecution> findDeliverableResults(RunId runId) { return List.of(); }
            @Override public Optional<ClaimedToolExecution> claimNextExecutable(String workerId) {
                return Optional.ofNullable(claim.get());
            }
            @Override public void completeExecution(PlatformToolExecution outcome) { completed.set(outcome); }
            @Override public void requeueRunIfSettled(RunId runId) { }
        };
        ToolApprovalRepository approvals = new ToolApprovalRepository() {
            @Override public Optional<ToolApproval> findByToolExecutionId(String toolExecutionId) { return Optional.empty(); }
            @Override public void createPending(PlatformToolExecution e, long requestedForUserId) { parked.set(e); }
            @Override public boolean decide(String toolExecutionId, ApprovalState decision, long decidedByUserId, String reason) { return false; }
        };
        ToolExecutionGatewayService gateway = new ToolExecutionGatewayService() {
            @Override public ToolPreparation prepare(PlatformToolExecution e, AgentRun r) {
                if (gatewayError != null) throw gatewayError;
                return preparation;
            }
            @Override public ToolExecutionResult execute(PlatformToolExecution e, AgentRun r, ToolPreparation p) {
                return executionResult;
            }
        };
        worker = new ToolExecutionWorker(executions, approvals, gateway, CLOCK);
    }

    @Test
    void returnsFalseWhenNothingToClaim() {
        claim.set(null);
        assertFalse(worker.executeNext("worker-1"));
        assertNull(completed.get());
    }

    @Test
    void deniedSettlesTerminalWithoutExecuting() {
        preparation = ToolPreparation.denied("CAPABILITY_DISABLED", "该能力已停用。");

        assertTrue(worker.executeNext("worker-1"));

        PlatformToolExecution settled = completed.get();
        assertEquals(ToolExecutionState.DENIED, settled.state());
        assertEquals("CAPABILITY_DISABLED", settled.errorCode());
        assertEquals(ResultDeliveryState.READY, settled.resultDeliveryState());
        assertTrue(settled.resultJson().contains("\"success\":false"));
        assertTrue(settled.resultJson().contains("该能力已停用。"));
        assertNotNull(settled.resultDigest());
        assertNull(parked.get(), "拒绝路径不得停靠审批");
    }

    @Test
    void approvalRequiredParksWithoutCompleting() {
        preparation = ToolPreparation.approvalRequired(CAPABILITY);

        assertTrue(worker.executeNext("worker-1"));

        assertSame(execution, parked.get(), "必须停靠到审批（§39 边界路径）");
        assertNull(completed.get(), "审批未决前不得落定结果");
    }

    @Test
    void allowedSuccessCompletesWithReceiptAndEnvelope() {
        preparation = ToolPreparation.allowed(CAPABILITY);
        executionResult = ToolExecutionResult.succeeded("已成功预定会议室 A-201。", "BK-1");

        assertTrue(worker.executeNext("worker-1"));

        PlatformToolExecution settled = completed.get();
        assertEquals(ToolExecutionState.SUCCEEDED, settled.state());
        assertEquals("BK-1", settled.externalReceipt());
        assertNull(settled.errorCode());
        assertTrue(settled.resultJson().contains("\"success\":true"));
        assertTrue(settled.resultJson().contains("已成功预定会议室 A-201。"));
        assertEquals(ResultDeliveryState.READY, settled.resultDeliveryState());
    }

    @Test
    void allowedBusinessFailureCompletesFailedWithCode() {
        preparation = ToolPreparation.allowed(CAPABILITY);
        executionResult = ToolExecutionResult.failed("MEETING_ROOM_CONFLICT", "A-201 在该时段已被占用。");

        assertTrue(worker.executeNext("worker-1"));

        PlatformToolExecution settled = completed.get();
        assertEquals(ToolExecutionState.FAILED, settled.state());
        assertEquals("MEETING_ROOM_CONFLICT", settled.errorCode());
        assertTrue(settled.resultJson().contains("\"success\":false"));
    }

    @Test
    void unknownOutcomeSettlesReconciliationRequiredAndNeverRetries() {
        preparation = ToolPreparation.allowed(CAPABILITY);
        executionResult = ToolExecutionResult.unknown("执行结果未知，请人工核对会议室状态。");

        assertTrue(worker.executeNext("worker-1"));

        PlatformToolExecution settled = completed.get();
        assertEquals(ToolExecutionState.RECONCILIATION_REQUIRED, settled.state(),
                "§43.4：结果未知必须停下等待对账，绝不盲目重试");
        assertEquals("TOOL_RESULT_UNKNOWN", settled.errorCode());
    }

    @Test
    void gatewayExceptionSettlesFailedWithSafeMessage() {
        gatewayError = new IllegalStateException("db is down");

        assertTrue(worker.executeNext("worker-1"));

        PlatformToolExecution settled = completed.get();
        assertEquals(ToolExecutionState.FAILED, settled.state());
        assertEquals("TOOL_WORKER_ERROR", settled.errorCode());
        assertTrue(settled.resultJson().contains("操作执行失败，请稍后重试。"),
                "异常兜底文案必须安全，不得泄漏内部异常细节");
        assertFalse(settled.resultJson().contains("db is down"));
    }
}
