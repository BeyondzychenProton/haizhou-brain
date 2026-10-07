package com.haizhuo.brain.infrastructure.session;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createRunAndToolTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createSessionAndGuidanceTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createSessionEventTable;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.eventTypes;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertExecution;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRun;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertSession;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.runState;
import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.HarnessRunSpec;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.RunGuidance;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.session.AgentSession;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcSessionRunStore 的会话/运行持久化语义：createRun 同事务冻结 run spec 与
 * USER_INPUT 首事件（§10.2）、幂等重放与冲突拒绝、属主校验，以及 §47 取消全矩阵
 * （QUEUED 直接取消 / RUNNING→CANCELLING / WAITING_TOOL 执行中拒绝 / WAITING_CONFIRMATION
 * 关闭审批）与运行中引导的一次性消费。
 */
class JdbcSessionRunStoreTest {

    private static final UserId OWNER = new UserId(42);
    private static final SessionId SESSION = new SessionId("session-1");

    private JdbcTemplate jdbc;
    private JdbcSessionRunStore store;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("sessionrun");
        createRunAndToolTables(jdbc);
        createSessionAndGuidanceTables(jdbc);
        createSessionEventTable(jdbc);
        store = new JdbcSessionRunStore(jdbc, new JdbcSessionEventProjector(jdbc));
    }

    @Test
    void fixedIdentityRoundTripsAndRejectsVersionDrift() {
        var session = new AgentSession(SESSION, OWNER, 1L, AgentSession.Status.ACTIVE, T0, T0, 0, 2L, false);
        store.createSession(session);
        assertEquals(session, store.findSession(SESSION, OWNER).orElseThrow());
        assertEquals(session, store.findSessions(OWNER, 10).get(0));
        assertThrows(IllegalStateException.class,
                () -> store.createRun(newRun("wrong-version", "req", "digest"), "输入", runSpec("wrong-version")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run", Integer.class));
    }

    @Test
    void legacyPinIsOwnerScopedAndNeverOverwritesWinningVersion() {
        store.createSession(new AgentSession(SESSION, OWNER, 1L, AgentSession.Status.ACTIVE, T0, T0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> store.pinDefinitionVersion(SESSION, new UserId(99), 2));
        assertEquals(1L, store.pinDefinitionVersion(SESSION, OWNER, 1).definitionVersionId());
        assertEquals(1L, store.pinDefinitionVersion(SESSION, OWNER, 2).definitionVersionId());
        assertEquals(1, store.findSession(SESSION, OWNER).orElseThrow().rowVersion());
    }

    @Test
    void createRunFreezesSpecAndInputEventAtomically() {
        store.createSession(new AgentSession(SESSION, OWNER, 1L, AgentSession.Status.ACTIVE, T0, T0, 0));

        AgentRun run = newRun("run-1", "req-1", "digest-1");
        AgentRun created = store.createRun(run, "帮我查一下会议室", runSpec("run-1"));

        assertEquals("run-1", created.id().value());
        assertEquals(RunState.QUEUED, runStateValue("run-1"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_spec WHERE run_id='run-1'", Integer.class),
                "run spec 必须与 Run 同事务冻结（§10.2）");
        List<RunEvent> events = store.findEvents(new RunId("run-1"), OWNER, 0, 10);
        assertEquals(1, events.size());
        assertEquals("USER_INPUT", events.get(0).type());
        assertEquals("帮我查一下会议室", events.get(0).content());
        AgentSession session = store.findSession(SESSION, OWNER).orElseThrow();
        assertEquals(1, session.rowVersion(), "创建 Run 应推进会话版本");
    }

    @Test
    void createRunReplaysIdempotentlyAndRejectsConflicts() {
        store.createSession(new AgentSession(SESSION, OWNER, 1L, AgentSession.Status.ACTIVE, T0, T0, 0));
        store.createRun(newRun("run-1", "req-1", "digest-1"), "原始输入", runSpec("run-1"));

        AgentRun replay = store.createRun(newRun("run-2", "req-1", "digest-1"), "重复提交", runSpec("run-2"));
        assertEquals("run-1", replay.id().value(), "相同 clientRequestId+输入指纹必须幂等返回既有 Run");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_spec", Integer.class),
                "幂等重放不得重复冻结 spec");

        assertThrows(IllegalStateException.class,
                () -> store.createRun(newRun("run-3", "req-1", "digest-other"), "不同输入", runSpec("run-3")),
                "相同 clientRequestId 但输入指纹不同必须拒绝");
        AgentRun orphan = new AgentRun(new RunId("run-4"), new SessionId("session-missing"), OWNER, 1L, 1L,
                "req-4", "digest-4", RunState.QUEUED, Instant.now(), null, null);
        assertThrows(IllegalArgumentException.class,
                () -> store.createRun(orphan, "无会话输入", runSpec("run-4")),
                "不存在的会话必须拒绝");
    }

    @Test
    void createRunRejectsEmployeeMismatch() {
        store.createSession(new AgentSession(SESSION, OWNER, 1L, AgentSession.Status.ACTIVE, T0, T0, 0));
        AgentRun mismatched = new AgentRun(new RunId("run-1"), SESSION, OWNER, 2L, 1L,
                "req-1", "digest-1", RunState.QUEUED, T0, null, null);
        assertThrows(IllegalStateException.class, () -> store.createRun(mismatched, "输入", runSpec("run-1")),
                "Run 的员工必须与会话一致");
    }

    @Test
    void findsRunsEventsTimelineAndQueuePositionWithOwnership() {
        store.createSession(new AgentSession(SESSION, OWNER, 1L, AgentSession.Status.ACTIVE, T0, T0, 0));
        store.createRun(newRun("run-1", "req-1", "digest-1"), "第一条", runSpec("run-1"));
        store.createRun(newRun("run-2", "req-2", "digest-2"), "第二条", runSpec("run-2"));

        assertEquals(2, store.findRuns(SESSION, OWNER, 10).size());
        assertEquals(0, store.findRuns(SESSION, new UserId(43), 10).size(), "属主隔离");
        assertEquals(0, store.findEvents(new RunId("run-1"), new UserId(43), 0, 10).size(), "事件同样属主隔离");
        assertEquals(2, store.findTimeline(SESSION, OWNER, 10).size());
        assertEquals(2, store.queuePosition(new RunId("run-2"), OWNER), "同会话先到的 Run 排在前面");
        assertEquals(1, store.queuePosition(new RunId("run-1"), OWNER));
        jdbc.update("UPDATE platform_agent_run SET state='RUNNING' WHERE run_id='run-1'");
        assertEquals(0, store.queuePosition(new RunId("run-1"), OWNER), "非 QUEUED 无排队位置");
    }

    @Test
    void cancelQueuedRunCancelsDirectly() {
        insertSession(jdbc, "session-1", 42L, 1L, "ACTIVE");
        insertRun(jdbc, "run-1", "session-1", "QUEUED");

        AgentRun cancelled = store.cancel(new RunId("run-1"), OWNER);
        assertEquals(RunState.CANCELLED, cancelled.state());
        assertNotNull(jdbc.queryForObject("SELECT finished_at FROM platform_agent_run WHERE run_id='run-1'", Timestamp.class));
        assertNotNull(jdbc.queryForObject("SELECT cancel_requested_at FROM platform_agent_run WHERE run_id='run-1'", Timestamp.class));
        assertEquals(List.of("RUN_CANCELLED"), eventTypes(jdbc, "run-1"));
    }

    @Test
    void cancelRunningRunMarksCancellingUntilRuntimeConfirms() {
        insertSession(jdbc, "session-1", 42L, 1L, "ACTIVE");
        insertRun(jdbc, "run-1", "session-1", "RUNNING");

        AgentRun cancelling = store.cancel(new RunId("run-1"), OWNER);
        assertEquals(RunState.CANCELLING, cancelling.state(), "§47.2：运行中只打 durable 取消标记");
        assertTrue(store.isCancellationRequested(new RunId("run-1")));
        assertEquals(List.of("RUN_CANCEL_REQUESTED"), eventTypes(jdbc, "run-1"));

        AgentRun again = store.cancel(new RunId("run-1"), OWNER);
        assertEquals(RunState.CANCELLING, again.state(), "CANCELLING 重复取消是幂等空操作");
        assertEquals(List.of("RUN_CANCEL_REQUESTED"), eventTypes(jdbc, "run-1"), "重复取消不得重复写事件");
    }

    @Test
    void cancelWaitingToolRefusesWhileExecutingButCancelsPendingWork() {
        insertSession(jdbc, "session-1", 42L, 1L, "ACTIVE");
        insertRun(jdbc, "run-1", "session-1", "WAITING_TOOL");
        insertExecution(jdbc, "exec-inflight", "run-1", "tu-1", "EXECUTING", null);

        assertThrows(IllegalStateException.class, () -> store.cancel(new RunId("run-1"), OWNER),
                "§47.3：工具执行中是已离开的副作用，必须诚实拒绝");
        assertEquals("WAITING_TOOL", runState(jdbc, "run-1"));

        jdbc.update("UPDATE platform_tool_execution SET state='SUCCEEDED',result_delivery_state='READY' WHERE tool_execution_id='exec-inflight'");
        insertExecution(jdbc, "exec-pending", "run-1", "tu-2", "REQUESTED", null);
        insertExecution(jdbc, "exec-approved", "run-1", "tu-3", "APPROVED", null);

        AgentRun cancelled = store.cancel(new RunId("run-1"), OWNER);
        assertEquals(RunState.CANCELLED, cancelled.state());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM platform_tool_execution WHERE tool_execution_id='exec-pending'", String.class));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM platform_tool_execution WHERE tool_execution_id='exec-approved'", String.class));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT state FROM platform_tool_execution WHERE tool_execution_id='exec-inflight'", String.class),
                "已完成执行不受影响");
        assertEquals(List.of("RUN_CANCELLED"), eventTypes(jdbc, "run-1"));
    }

    @Test
    void cancelWaitingConfirmationClosesApprovalAndCancels() {
        insertSession(jdbc, "session-1", 42L, 1L, "ACTIVE");
        insertRun(jdbc, "run-1", "session-1", "WAITING_CONFIRMATION");
        insertExecution(jdbc, "exec-1", "run-1", "tu-1", "APPROVAL_REQUIRED", null);
        jdbc.update("INSERT INTO platform_tool_approval(approval_id,tool_execution_id,run_id,requested_for_user_id,"
                + "decision,created_at) VALUES('ap-1','exec-1','run-1',42,'PENDING',?)", Timestamp.from(T0));

        AgentRun cancelled = store.cancel(new RunId("run-1"), OWNER);
        assertEquals(RunState.CANCELLED, cancelled.state());
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT decision FROM platform_tool_approval WHERE approval_id='ap-1'", String.class),
                "§47.4：待决审批必须被关闭");
        assertEquals("运行已取消", jdbc.queryForObject(
                "SELECT reason FROM platform_tool_approval WHERE approval_id='ap-1'", String.class));
        assertNotNull(jdbc.queryForObject(
                "SELECT decided_at FROM platform_tool_approval WHERE approval_id='ap-1'", Timestamp.class));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM platform_tool_execution WHERE tool_execution_id='exec-1'", String.class));
        assertEquals(List.of("RUN_CANCELLED"), eventTypes(jdbc, "run-1"));
    }

    @Test
    void cancelTerminalRunIsRejected() {
        insertSession(jdbc, "session-1", 42L, 1L, "ACTIVE");
        insertRun(jdbc, "run-1", "session-1", "SUCCEEDED");
        assertThrows(IllegalStateException.class, () -> store.cancel(new RunId("run-1"), OWNER));
        assertThrows(IllegalArgumentException.class, () -> store.cancel(new RunId("run-missing"), OWNER));
    }

    @Test
    void guidanceAcceptedOnlyWhileRunningAndConsumedOnce() {
        insertSession(jdbc, "session-1", 42L, 1L, "ACTIVE");
        insertRun(jdbc, "run-1", "session-1", "QUEUED");
        assertThrows(IllegalStateException.class,
                () -> store.addGuidance(new RunId("run-1"), OWNER, "USER", "请先别订房"),
                "只有 RUNNING 才接受引导");

        jdbc.update("UPDATE platform_agent_run SET state='RUNNING' WHERE run_id='run-1'");
        RunGuidance guidance = store.addGuidance(new RunId("run-1"), OWNER, "USER", "请先别订房");
        assertEquals(RunGuidance.Status.PENDING, guidance.status());
        assertEquals(List.of("RUN_GUIDANCE_RECEIVED"), eventTypes(jdbc, "run-1"));

        List<RunGuidanceMessage> consumed = store.consumeGuidance(new RunId("run-1"));
        assertEquals(1, consumed.size());
        assertEquals("请先别订房", consumed.get(0).content());
        assertEquals("CONSUMED", jdbc.queryForObject(
                "SELECT status FROM platform_agent_run_guidance WHERE guidance_id=?", String.class, guidance.id()));
        assertTrue(store.consumeGuidance(new RunId("run-1")).isEmpty(), "一次性消费，不得重复投递");
        assertEquals(List.of("RUN_GUIDANCE_RECEIVED", "RUN_GUIDANCE_CONSUMED"), eventTypes(jdbc, "run-1"));
    }

    private static AgentRun newRun(String runId, String clientRequestId, String inputDigest) {
        return new AgentRun(new RunId(runId), SESSION, OWNER, 1L, 1L,
                clientRequestId, inputDigest, RunState.QUEUED, Instant.now(), null, null);
    }

    private static HarnessRunSpec runSpec(String runId) {
        return new HarnessRunSpec(new RunId(runId), 1L, 1L, "b".repeat(64),
                "[\"meeting_room_search\"]", "t".repeat(64), "e".repeat(64), null, Instant.now());
    }

    private RunState runStateValue(String runId) {
        return RunState.valueOf(jdbc.queryForObject("SELECT state FROM platform_agent_run WHERE run_id=?",
                String.class, runId));
    }
}
