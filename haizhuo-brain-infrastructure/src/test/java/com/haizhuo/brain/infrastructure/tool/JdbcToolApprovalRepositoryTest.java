package com.haizhuo.brain.infrastructure.tool;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createRunAndToolTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.eventTypes;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.executionState;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertExecution;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRun;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.runState;
import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.platform.tool.ApprovalState;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ResultDeliveryState;
import com.haizhuo.brain.platform.tool.ToolApproval;
import com.haizhuo.brain.platform.tool.ToolExecutionState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcToolApprovalRepository 的审批语义（spec §39/§84/§49）：停靠（execution
 * APPROVAL_REQUIRED + PENDING 审批 + Run WAITING_CONFIRMATION）、一次性决定（重复决定
 * 幂等返回 false）、拒绝时写入安全文案并置 READY 供 Harness 收尾、事件名与 §49 对齐
 * （TOOL_APPROVED / TOOL_REJECTED）。
 */
class JdbcToolApprovalRepositoryTest {

    private JdbcTemplate jdbc;
    private JdbcToolApprovalRepository repository;
    private JdbcToolExecutionRepository executions;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("approval");
        createRunAndToolTables(jdbc);
        repository = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.transactional(new JdbcToolApprovalRepository(jdbc), jdbc);
        executions = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.transactional(new JdbcToolExecutionRepository(jdbc), jdbc);
    }

    @Test
    void createPendingParksExecutionApprovalAndRun() {
        insertRun(jdbc, "run-1", "session-1", "WAITING_TOOL");
        insertExecution(jdbc, "exec-1", "run-1", "tu-1", "REQUESTED", null);
        PlatformToolExecution execution = executions.findById("exec-1").orElseThrow();

        repository.createPending(execution, 42L);

        assertEquals("APPROVAL_REQUIRED", executionState(jdbc, "exec-1"));
        ToolApproval approval = repository.findByToolExecutionId("exec-1").orElseThrow();
        assertEquals(ApprovalState.PENDING, approval.decision());
        assertEquals(42L, approval.requestedFor().value());
        assertNull(approval.decidedAt());
        assertEquals("WAITING_CONFIRMATION", runState(jdbc, "run-1"));
        assertEquals(List.of("TOOL_APPROVAL_REQUIRED"), eventTypes(jdbc, "run-1"));
    }

    @Test
    void createPendingRejectsTerminalExecution() {
        insertRun(jdbc, "run-1", "session-1", "WAITING_TOOL");
        insertExecution(jdbc, "exec-1", "run-1", "tu-1", "SUCCEEDED", "READY");
        PlatformToolExecution terminal = executions.findById("exec-1").orElseThrow();

        assertThrows(IllegalStateException.class, () -> repository.createPending(terminal, 42L),
                "终态执行不得停靠审批");
    }

    @Test
    void approveMovesExecutionBackToExecutableAndRunToWaitingTool() {
        insertRun(jdbc, "run-1", "session-1", "WAITING_TOOL");
        insertExecution(jdbc, "exec-1", "run-1", "tu-1", "REQUESTED", null);
        repository.createPending(executions.findById("exec-1").orElseThrow(), 42L);

        assertTrue(repository.decide("exec-1", ApprovalState.APPROVED, 7L, "同意"));

        ToolApproval decided = repository.findByToolExecutionId("exec-1").orElseThrow();
        assertEquals(ApprovalState.APPROVED, decided.decision());
        assertEquals(7L, decided.decidedBy().value());
        assertEquals("同意", decided.reason());
        assertNotNull(decided.decidedAt());
        assertEquals("APPROVED", executionState(jdbc, "exec-1"), "批准后执行回到可认领状态");
        assertEquals("WAITING_TOOL", runState(jdbc, "run-1"));
        assertEquals(List.of("TOOL_APPROVAL_REQUIRED", "TOOL_APPROVED"), eventTypes(jdbc, "run-1"));

        assertFalse(repository.decide("exec-1", ApprovalState.APPROVED, 7L, "重复批准"),
                "§84：重复决定幂等返回 false，不得改写事实");
        assertEquals("同意", repository.findByToolExecutionId("exec-1").orElseThrow().reason());
    }

    @Test
    void rejectWritesSafeDeniedResultAndRequeuesRun() {
        insertRun(jdbc, "run-1", "session-1", "WAITING_TOOL");
        insertExecution(jdbc, "exec-1", "run-1", "tu-1", "REQUESTED", null);
        repository.createPending(executions.findById("exec-1").orElseThrow(), 42L);

        assertTrue(repository.decide("exec-1", ApprovalState.REJECTED, 7L, "预算超限，不同意"));

        PlatformToolExecution denied = executions.findById("exec-1").orElseThrow();
        assertEquals(ToolExecutionState.DENIED, denied.state());
        assertEquals("APPROVAL_DENIED", denied.errorCode());
        assertEquals(ResultDeliveryState.READY, denied.resultDeliveryState(),
                "拒绝结果必须 READY，供 Harness 用自然语言收尾（§39.3）");
        assertNotNull(denied.resultJson());
        assertTrue(denied.resultJson().contains("该操作未获得授权/确认。"),
                "拒绝文案必须是安全兜底语，不得泄漏内部审批原因");
        assertFalse(denied.resultJson().contains("预算超限"));
        assertNotNull(denied.resultDigest());
        assertEquals("QUEUED", runState(jdbc, "run-1"), "无未决执行时 Run 重新排队收尾");
        assertEquals(List.of("TOOL_APPROVAL_REQUIRED", "TOOL_REJECTED", "RUN_RESUME_QUEUED"),
                eventTypes(jdbc, "run-1"));

        assertFalse(repository.decide("exec-1", ApprovalState.APPROVED, 7L, "反悔"),
                "已拒绝的决定不得被改写");
    }

    @Test
    void decideOnMissingApprovalReturnsFalse() {
        assertFalse(repository.decide("exec-missing", ApprovalState.APPROVED, 7L, "无此执行"));
    }
}
