package com.haizhuo.brain.infrastructure.tool;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createRunAndToolTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.eventTypes;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.executionState;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertExecution;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRun;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.runState;
import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.tool.ClaimedToolExecution;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ResultDeliveryState;
import com.haizhuo.brain.platform.tool.ToolExecutionState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcToolExecutionRepository 的 durable 语义（spec §55.1/§45）：claim→EXECUTING、
 * Tx-05（终态+READY+事件+Run 重排队原子落定）、一次性可投递结果视图、拒绝对非
 * EXECUTING 记录写入结果。
 */
class JdbcToolExecutionRepositoryTest {

    private JdbcTemplate jdbc;
    private JdbcToolExecutionRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("toolexec");
        createRunAndToolTables(jdbc);
        repository = new JdbcToolExecutionRepository(jdbc);
    }

    @Test
    void claimTransitionsToExecutingAndJoinsOwningRun() {
        insertRun(jdbc, "run-1", "session-1", 42L, "WAITING_TOOL");
        insertExecution(jdbc, "exec-1", "run-1", "tu-1", "REQUESTED", null);

        ClaimedToolExecution claimed = repository.claimNextExecutable("worker-1").orElseThrow();
        assertEquals(ToolExecutionState.EXECUTING, claimed.execution().state());
        assertEquals("run-1", claimed.run().id().value());
        assertEquals("session-1", claimed.run().sessionId().value());
        assertEquals(42L, claimed.run().userId().value());
        assertEquals(RunState.WAITING_TOOL, claimed.run().state());

        assertTrue(repository.claimNextExecutable("worker-1").isEmpty(),
                "EXECUTING 中的记录不应被重复认领");
    }

    @Test
    void claimPicksApprovedButNotApprovalRequired() {
        insertRun(jdbc, "run-1", "session-1", "WAITING_TOOL");
        insertExecution(jdbc, "exec-pending", "run-1", "tu-1", "APPROVAL_REQUIRED", null);
        insertExecution(jdbc, "exec-approved", "run-1", "tu-2", "APPROVED", null);

        ClaimedToolExecution claimed = repository.claimNextExecutable("worker-1").orElseThrow();
        assertEquals("exec-approved", claimed.execution().id(), "只有 APPROVED 可被执行，审批中的必须原地等待");
        assertTrue(repository.claimNextExecutable("worker-1").isEmpty());
    }

    @Test
    void completeExecutionSettlesResultRequeuesRunAndEmitsEvents() {
        insertRun(jdbc, "run-1", "session-1", "WAITING_TOOL");
        insertExecution(jdbc, "exec-1", "run-1", "tu-1", "REQUESTED", null);
        ClaimedToolExecution claimed = repository.claimNextExecutable("worker-1").orElseThrow();

        repository.completeExecution(outcome(claimed.execution(), ToolExecutionState.SUCCEEDED,
                "{\"success\":true,\"content\":\"已预订 A-201\"}", null, "BK-1"));

        PlatformToolExecution settled = repository.findById("exec-1").orElseThrow();
        assertEquals(ToolExecutionState.SUCCEEDED, settled.state());
        assertEquals(ResultDeliveryState.READY, settled.resultDeliveryState(), "结果必须 READY 等待一次性投递（§45）");
        assertEquals("BK-1", settled.externalReceipt());
        assertNotNull(settled.resultDigest());
        assertEquals("QUEUED", runState(jdbc, "run-1"), "无未决执行时 Run 必须重新排队（Tx-05）");
        assertEquals(List.of("TOOL_RESULT_READY", "RUN_RESUME_QUEUED"), eventTypes(jdbc, "run-1"));
    }

    @Test
    void completeExecutionKeepsRunWaitingWhileOthersAreOpen() {
        insertRun(jdbc, "run-1", "session-1", "WAITING_TOOL");
        insertExecution(jdbc, "exec-1", "run-1", "tu-1", "REQUESTED", null);
        insertExecution(jdbc, "exec-2", "run-1", "tu-2", "REQUESTED", null);
        ClaimedToolExecution claimed = repository.claimNextExecutable("worker-1").orElseThrow();
        assertEquals("exec-1", claimed.execution().id());

        repository.completeExecution(outcome(claimed.execution(), ToolExecutionState.SUCCEEDED,
                "{\"success\":true,\"content\":\"完成\"}", null, null));

        assertEquals("WAITING_TOOL", runState(jdbc, "run-1"), "仍有未决执行时 Run 不得提前重排队");
        assertEquals(List.of("TOOL_RESULT_READY"), eventTypes(jdbc, "run-1"));
    }

    @Test
    void completeExecutionRejectsRecordThatIsNotExecuting() {
        insertRun(jdbc, "run-1", "session-1", "WAITING_TOOL");
        insertExecution(jdbc, "exec-1", "run-1", "tu-1", "REQUESTED", null);
        PlatformToolExecution notExecuting = repository.findById("exec-1").orElseThrow();

        assertThrows(IllegalStateException.class, () -> repository.completeExecution(
                outcome(notExecuting, ToolExecutionState.SUCCEEDED, "{}", null, null)),
                "只允许 EXECUTING → 终态，绕过 claim 的写入必须失败");
        assertEquals("REQUESTED", executionState(jdbc, "exec-1"));
    }

    @Test
    void findDeliverableResultsReturnsReadyTerminalOnly() {
        insertRun(jdbc, "run-1", "session-1", "WAITING_TOOL");
        insertExecution(jdbc, "exec-ok-ready", "run-1", "tu-1", "SUCCEEDED", "READY");
        insertExecution(jdbc, "exec-ok-delivered", "run-1", "tu-2", "SUCCEEDED", "DELIVERED");
        insertExecution(jdbc, "exec-denied-ready", "run-1", "tu-3", "DENIED", "READY");
        insertExecution(jdbc, "exec-failed-ready", "run-1", "tu-4", "FAILED", "READY");
        insertExecution(jdbc, "exec-recon-ready", "run-1", "tu-5", "RECONCILIATION_REQUIRED", "READY");
        insertExecution(jdbc, "exec-executing", "run-1", "tu-6", "EXECUTING", null);
        insertExecution(jdbc, "exec-ok-nodelivery", "run-1", "tu-7", "SUCCEEDED", null);

        List<String> deliverable = repository.findDeliverableResults(new RunId("run-1")).stream()
                .map(PlatformToolExecution::id).toList();
        assertEquals(List.of("exec-denied-ready", "exec-failed-ready", "exec-ok-ready", "exec-recon-ready"),
                deliverable, "只有 READY 的终态结果可投递；DELIVERED/进行中/无投递态一律排除（§45.1）"
                        + "（created_at 相同，按 tool_execution_id 字典序）");
    }

    @Test
    void requeueRunIfSettledOnlyWhenNoOpenExecutions() {
        insertRun(jdbc, "run-settled", "session-1", "WAITING_CONFIRMATION");
        insertExecution(jdbc, "exec-denied", "run-settled", "tu-1", "DENIED", "READY");
        repository.requeueRunIfSettled(new RunId("run-settled"));
        assertEquals("QUEUED", runState(jdbc, "run-settled"));

        insertRun(jdbc, "run-terminal", "session-2", "SUCCEEDED");
        repository.requeueRunIfSettled(new RunId("run-terminal"));
        assertEquals("SUCCEEDED", runState(jdbc, "run-terminal"), "终态 Run 永远不得被重排队");

        insertRun(jdbc, "run-open", "session-3", "WAITING_TOOL");
        insertExecution(jdbc, "exec-open", "run-open", "tu-1", "EXECUTING", null);
        repository.requeueRunIfSettled(new RunId("run-open"));
        assertEquals("WAITING_TOOL", runState(jdbc, "run-open"), "有未决执行时不得重排队");
    }

    private static PlatformToolExecution outcome(PlatformToolExecution execution, ToolExecutionState state,
                                                 String resultJson, String errorCode, String receipt) {
        return new PlatformToolExecution(execution.id(), execution.runId(), execution.toolUseId(),
                execution.capabilityRevisionId(), execution.toolName(), execution.inputJson(),
                execution.inputDigest(), state, execution.idempotencyKey(), receipt, resultJson,
                "r".repeat(64), ResultDeliveryState.READY, errorCode, execution.createdAt(), T0);
    }
}
