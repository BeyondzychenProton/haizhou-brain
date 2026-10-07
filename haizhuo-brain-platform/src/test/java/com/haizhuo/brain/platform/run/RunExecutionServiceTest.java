package com.haizhuo.brain.platform.run;

import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.channel.ChannelReplyEnqueuer;
import com.haizhuo.brain.platform.channel.ChannelTurnPromoter;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundle;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleRepository;
import com.haizhuo.brain.platform.employee.runtime.PublishedToolSchema;
import com.haizhuo.brain.platform.harness.SessionBridgeService;
import com.haizhuo.brain.platform.harness.SessionBridgeSnapshot;
import com.haizhuo.brain.platform.harness.SessionBridgeSnapshotRepository;
import com.haizhuo.brain.platform.harness.SessionHarnessBinding;
import com.haizhuo.brain.platform.harness.SessionHarnessBindingRepository;
import com.haizhuo.brain.platform.session.AgentSession;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ToolApproval;
import com.haizhuo.brain.platform.tool.ToolExecutionRepository;
import com.haizhuo.brain.platform.tool.ToolExecutionState;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.event.AgentPlanUpdatedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCancelledEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.event.AgentTextDeltaEvent;
import com.haizhuo.brain.runtime.api.event.AgentToolSuspendedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.event.PendingExternalToolCall;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.ExternalToolResult;
import com.haizhuo.brain.runtime.api.model.ExternalToolResultExecutionInput;
import com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * RunExecutionService 的核心循环（spec §13/§7.6/§43.1/§45.1/§46）：冻结请求的组装
 * （bundle/binding/spec/首事件/恢复结果）、四种终止落定（suspend/complete/cancel/fail）、
 * 目录外工具拒绝、空流与运行时异常兜底。全部协作者用内存 fake，AgentRuntime 返回
 * 编排好的事件流。
 */
class RunExecutionServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(120);
    private static final UserId OWNER = new UserId(42);
    private static final SessionId SESSION = new SessionId("session-1");
    private static final RunId RUN = new RunId("run-1");

    private FakeExecutionStore store;
    private FakeToolExecutions toolExecutions;
    private AtomicReference<Function<AgentExecutionRequest, Flux<BrainAgentEvent>>> runtimeBehavior;
    private AtomicReference<AgentExecutionRequest> capturedRequest;
    private FakeRealtimeEvents realtimeEvents;
    private FakeChannelReplies channelReplies;
    private FakeChannelTurns channelTurns;
    private RunExecutionService service;
    private FakeRuns runs;

    @BeforeEach
    void setUp() {
        store = new FakeExecutionStore();
        toolExecutions = new FakeToolExecutions();
        capturedRequest = new AtomicReference<>();
        runtimeBehavior = new AtomicReference<>(request -> Flux.just(
                new AgentTextDeltaEvent(RUN, "你"), new AgentRunCompletedEvent(RUN, "你好，已完成。")));
        AgentRuntime runtime = request -> {
            capturedRequest.set(request);
            return runtimeBehavior.get().apply(request);
        };
        realtimeEvents = new FakeRealtimeEvents();
        channelReplies = new FakeChannelReplies();
        channelTurns = new FakeChannelTurns();
        runs = new FakeRuns();
        service = new RunExecutionService(store, new FakeBundles(), bridgeService(), new FakeSnapshots(),
                runs, toolExecutions, runtime, realtimeEvents, channelReplies, channelTurns,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void directSessionDoesNotReadOrWriteBridge() {
        runs.direct = true;
        // 这些旧仓储一旦被调用就失败，证明直连链路没有隐藏的 Bridge IO。
        SessionBridgeSnapshotRepository forbidden = new SessionBridgeSnapshotRepository() {
            public Optional<SessionBridgeSnapshot> findById(long id) { throw new AssertionError("Bridge read"); }
            public SessionBridgeSnapshot save(SessionBridgeSnapshot snapshot) { throw new AssertionError("Bridge write"); }
        };
        AgentRuntime runtime = request -> {
            capturedRequest.set(request);
            return Flux.just(new AgentRunCompletedEvent(RUN, "完成"));
        };
        service = new RunExecutionService(store, new FakeBundles(), bridgeService(), forbidden,
                runs, toolExecutions, runtime, realtimeEvents, channelReplies, channelTurns,
                Clock.fixed(T0, ZoneOffset.UTC));
        assertTrue(service.executeNext("worker-1", TTL));
        assertEquals(SESSION.value(), capturedRequest.get().binding().harnessSessionKey());
        assertNull(capturedRequest.get().binding().bridgeContext());
        assertNull(capturedRequest.get().binding().bridgeSnapshotHash());
        assertFalse(capturedRequest.get().binding().stateRequired());
        assertNull(store.failedCode);
    }

    @Test
    void continuedDirectSessionRequiresExistingRuntimeState() {
        runs.direct = true;
        runs.runtimeHistory = true;
        service.executeNext("worker-1", TTL);
        assertTrue(capturedRequest.get().binding().stateRequired());
    }

    @Test
    void returnsFalseWhenNoQueuedRun() {
        store.claim = null;
        assertFalse(service.executeNext("worker-1", TTL));
    }

    @Test
    void completedRunSettlesWithFrozenRequest() {
        assertTrue(service.executeNext("worker-1", TTL));

        assertEquals("你好，已完成。", store.completedResult);
        assertEquals(List.of(), store.completedDeliveredIds);
        assertEquals(List.of("你"), realtimeEvents.textDeltas,
                "文本增量只走瞬时实时端口，不改变持久终态内容");
        assertEquals(List.of("attempt-1"), realtimeEvents.attemptIds);
        assertEquals(List.of(1L), realtimeEvents.streamOffsets);

        AgentExecutionRequest request = capturedRequest.get();
        assertEquals(1L, request.tenantId().value(), "P0 单租户");
        assertEquals(OWNER, request.userId());
        assertEquals("attempt-1", request.attemptId());
        assertEquals(1L, request.fenceToken());
        assertInstanceOf(UserPromptExecutionInput.class, request.input());
        assertEquals("帮我订明天下午的会议室", ((UserPromptExecutionInput) request.input()).content(),
                "初始输入必须取自已冻结的 USER_INPUT 首事件（§10.2）");
        assertEquals("行政助理", request.definition().employeeName());
        assertEquals("b".repeat(64), request.definition().definitionBundleHash());
        assertEquals("defws:" + "b".repeat(64), request.definition().workspaceProjectionKey());
        assertEquals(2, request.definition().toolCatalog().size());
        assertEquals(java.util.Set.of("meeting_room_search"), request.constraints().modelVisibleToolNames());
        assertEquals("hs-1", request.binding().harnessSessionKey());
        assertEquals("ws-1", request.binding().workspaceRuntimeKey());
        assertEquals("h".repeat(64), request.binding().bridgeSnapshotHash());
        assertEquals("用户要订明天下午的会议室。", request.binding().bridgeContext());
        assertNull(store.failedCode);
    }

    @Test
    void realtimePublisherFailureDoesNotChangeDurableRunOutcome() {
        realtimeEvents.failPublish = true;

        assertTrue(service.executeNext("worker-1", TTL));

        assertEquals("你好，已完成。", store.completedResult);
        assertNull(store.failedCode);
    }

    @Test
    void terminalRunAttemptsToPromoteWaitingChannelTurns() {
        runtimeBehavior.set(request -> Flux.just(new AgentRunCompletedEvent(RUN, "答复")));

        assertTrue(service.executeNext("worker-1", TTL));

        assertEquals(List.of(SESSION.value()), channelTurns.promoted,
                "Run 进入终态后必须尝试提升该会话的等待消息");

        channelTurns.fail = true;
        assertTrue(service.executeNext("worker-1", TTL));
        assertEquals("答复", store.completedResult, "提升失败不得改写已经落定的终态");
        assertNull(store.failedCode);
    }

    @Test
    void completedRunEnqueuesChannelReplyAndEnqueueFailureStaysIsolated() {
        runtimeBehavior.set(request -> Flux.just(new AgentRunCompletedEvent(RUN, "答复正文")));

        assertTrue(service.executeNext("worker-1", TTL));

        assertEquals(List.of("run-1:答复正文"), channelReplies.enqueued,
                "Run 有最终答复时必须排进出站队列；Web 会话由实现自行忽略");

        channelReplies.fail = true;
        assertTrue(service.executeNext("worker-1", TTL));
        assertEquals("答复正文", store.completedResult, "入队失败不得改写已经落定的终态");
        assertNull(store.failedCode);
    }

    @Test
    void planWritesArePersistedButPhaseOnlyEventsAreNot() {
        runtimeBehavior.set(request -> Flux.just(
                new AgentPlanUpdatedEvent(RUN, AgentPlanUpdatedEvent.Phase.ENTER, null),
                new AgentPlanUpdatedEvent(RUN, AgentPlanUpdatedEvent.Phase.WRITE, "第一步：确认需求\n第二步：执行"),
                new AgentPlanUpdatedEvent(RUN, AgentPlanUpdatedEvent.Phase.WRITE, "   "),
                new AgentPlanUpdatedEvent(RUN, AgentPlanUpdatedEvent.Phase.EXIT, null),
                new AgentRunCompletedEvent(RUN, "按计划完成")));

        assertTrue(service.executeNext("worker-1", TTL));

        assertEquals(List.of("第一步：确认需求\n第二步：执行"), store.planSnapshots,
                "只有带正文的写入落库；ENTER/EXIT 与空内容不产生快照");
        assertEquals("按计划完成", store.completedResult);
    }

    @Test
    void planSnapshotFailureDoesNotChangeDurableRunOutcome() {
        store.failPlanSnapshot = true;
        runtimeBehavior.set(request -> Flux.just(
                new AgentPlanUpdatedEvent(RUN, AgentPlanUpdatedEvent.Phase.WRITE, "计划正文"),
                new AgentRunCompletedEvent(RUN, "完成")));

        assertTrue(service.executeNext("worker-1", TTL));

        assertEquals("完成", store.completedResult, "计划投影失败只影响展示，不得改写业务终态");
        assertNull(store.failedCode);
    }

    @Test
    void suspendFreezesExecutionsApprovalsAndIdempotencyKeys() {
        runtimeBehavior.set(request -> Flux.just(new AgentToolSuspendedEvent(RUN, "reply-1",
                List.of(new PendingExternalToolCall("tu-1", "meeting_room_reserve", Map.of("roomId", "A-201"))))));

        assertTrue(service.executeNext("worker-1", TTL));

        assertEquals(RunState.WAITING_CONFIRMATION, store.waitingState, "含待确认工具时进入 WAITING_CONFIRMATION");
        assertEquals(1, store.suspendedExecutions.size());
        PlatformToolExecution frozen = store.suspendedExecutions.get(0);
        assertEquals("tu-1", frozen.toolUseId());
        assertEquals(102L, frozen.capabilityRevisionId());
        assertEquals(ToolExecutionState.APPROVAL_REQUIRED, frozen.state());
        String expectedInput = CanonicalJson.write(Map.of("roomId", "A-201"));
        assertEquals(expectedInput, frozen.inputJson());
        String expectedDigest = CanonicalJson.sha256Hex(expectedInput.getBytes(StandardCharsets.UTF_8));
        String expectedKey = CanonicalJson.sha256Hex(
                ("run-1|tu-1|102|" + expectedDigest).getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedKey, frozen.idempotencyKey(), "§43.1 幂等键必须按规格派生");
        assertEquals(1, store.suspendedApprovals.size());
        ToolApproval approval = store.suspendedApprovals.get(0);
        assertEquals(frozen.id(), approval.toolExecutionId());
        assertEquals(RUN, approval.runId());
        assertEquals(OWNER, approval.requestedFor());
        assertNull(store.completedResult);
    }

    @Test
    void suspendOfReadOnlyToolNeedsNoApproval() {
        runtimeBehavior.set(request -> Flux.just(new AgentToolSuspendedEvent(RUN, "reply-1",
                List.of(new PendingExternalToolCall("tu-9", "meeting_room_search", Map.of("roomId", "A-201"))))));

        assertTrue(service.executeNext("worker-1", TTL));

        assertEquals(RunState.WAITING_TOOL, store.waitingState);
        assertEquals(ToolExecutionState.REQUESTED, store.suspendedExecutions.get(0).state());
        assertEquals(0, store.suspendedApprovals.size(), "只读工具不需要人工确认");
    }

    @Test
    void resumeAssemblesDeliverableResultsIntoRuntimeInput() {
        store.claim = claim(ExecutionResumeReason.TOOL_RESULT_READY);
        toolExecutions.deliverables = List.of(
                deliverable("exec-a", "tu-a", ToolExecutionState.SUCCEEDED,
                        "{\"success\":true,\"content\":\"已预订 A-201\"}"),
                deliverable("exec-b", "tu-b", ToolExecutionState.FAILED,
                        "{\"success\":false,\"content\":\"该时段已被占用\"}"));
        runtimeBehavior.set(request -> Flux.just(new AgentRunCompletedEvent(RUN, "已处理两个工具结果。")));

        assertTrue(service.executeNext("worker-1", TTL));

        AgentExecutionRequest request = capturedRequest.get();
        ExternalToolResultExecutionInput input = assertInstanceOf(ExternalToolResultExecutionInput.class, request.input(),
                "TOOL_RESULT_READY 恢复必须携带工具结果输入");
        List<ExternalToolResult> results = input.results();
        assertEquals(2, results.size());
        assertEquals(new ExternalToolResult("tu-a", "meeting_room_search", true, "已预订 A-201"), results.get(0));
        assertEquals(new ExternalToolResult("tu-b", "meeting_room_search", false, "该时段已被占用"), results.get(1));
        assertEquals(List.of("exec-a", "exec-b"), store.completedDeliveredIds,
                "落定必须翻转这些结果的一次性投递守卫（§45.1）");
    }

    @Test
    void suspendAlsoFlipsDeliveryGuardForConsumedResults() {
        store.claim = claim(ExecutionResumeReason.TOOL_RESULT_READY);
        toolExecutions.deliverables = List.of(deliverable("exec-a", "tu-a",
                ToolExecutionState.SUCCEEDED, "{\"success\":true,\"content\":\"已预订\"}"));
        runtimeBehavior.set(request -> Flux.just(new AgentToolSuspendedEvent(RUN, "reply-1",
                List.of(new PendingExternalToolCall("tu-1", "meeting_room_search", Map.of())))));

        assertTrue(service.executeNext("worker-1", TTL));

        assertEquals(List.of("exec-a"), store.suspendDeliveredIds,
                "再次 suspend 说明 Harness 已接受恢复结果，必须同样翻转投递守卫");
    }

    @Test
    void toolCallOutsideCatalogFailsRun() {
        runtimeBehavior.set(request -> Flux.just(new AgentToolSuspendedEvent(RUN, "reply-1",
                List.of(new PendingExternalToolCall("tu-1", "not_in_catalog", Map.of())))));

        assertTrue(service.executeNext("worker-1", TTL));

        assertEquals("TOOL_OUT_OF_CATALOG", store.failedCode, "模型请求目录外工具必须拒绝落库");
        assertTrue(store.suspendedExecutions.isEmpty());
    }

    @Test
    void failedCancelledAndEmptyStreamsSettleRespectively() {
        runtimeBehavior.set(request -> Flux.just(new AgentRunFailedEvent(RUN, "模型调用失败")));
        assertTrue(service.executeNext("worker-1", TTL));
        assertEquals("AGENT_RUNTIME", store.failedCode);
        assertEquals("模型调用失败", store.failedMessage);

        store.reset();
        runtimeBehavior.set(request -> Flux.just(new AgentRunCancelledEvent(RUN, "已按请求取消")));
        assertTrue(service.executeNext("worker-1", TTL));
        assertEquals("已按请求取消", store.cancelledMessage);

        store.reset();
        runtimeBehavior.set(request -> Flux.empty());
        assertTrue(service.executeNext("worker-1", TTL));
        assertEquals("RUNTIME_EMPTY", store.failedCode, "空事件流必须显式失败而非悬挂");

        store.reset();
        runtimeBehavior.set(request -> Flux.error(new IllegalStateException("boom")));
        assertTrue(service.executeNext("worker-1", TTL));
        assertEquals("RUN_EXECUTION_ERROR", store.failedCode, "运行时异常兜底为安全失败");
        assertEquals("运行执行失败，请稍后重试。", store.failedMessage);
        assertFalse(store.failedMessage.contains("boom"));
    }

    @Test
    void missingBundleOrBridgeFailsRun() {
        store.claim = claim(ExecutionResumeReason.INITIAL_PROMPT, 999L);
        assertTrue(service.executeNext("worker-1", TTL));
        assertEquals("RUN_EXECUTION_ERROR", store.failedCode, "缺少冻结 bundle 必须快速失败");
    }

    private static ExecutionClaim claim(ExecutionResumeReason reason) {
        return claim(reason, 7L);
    }

    private static ExecutionClaim claim(ExecutionResumeReason reason, long definitionVersionId) {
        AgentRun run = new AgentRun(RUN, SESSION, OWNER, 1L, definitionVersionId,
                "req-1", "d".repeat(64), RunState.RUNNING, T0, T0, null);
        HarnessRunSpec spec = new HarnessRunSpec(RUN, definitionVersionId, 1L, "b".repeat(64),
                "[\"meeting_room_search\"]", "t".repeat(64), "e".repeat(64), null, T0);
        RunExecutionAttempt attempt = new RunExecutionAttempt("attempt-1", RUN, 1, "worker-1",
                "lease-1", 1L, T0.plusSeconds(120), T0, ExecutionAttemptState.RUNNING, T0, null);
        return new ExecutionClaim(run, spec, attempt, reason);
    }

    private static PlatformToolExecution deliverable(String id, String toolUseId,
                                                     ToolExecutionState state, String resultJson) {
        return new PlatformToolExecution(id, RUN, toolUseId, 101L, "meeting_room_search", "{}",
                "i".repeat(64), state, "k" + id + "0".repeat(61), null, resultJson, "r".repeat(64),
                com.haizhuo.brain.platform.tool.ResultDeliveryState.READY, null, T0, T0);
    }

    private static SessionBridgeService bridgeService() {
        SessionHarnessBinding binding = new SessionHarnessBinding(1L, OWNER, SESSION, 1L, 7L, 1,
                "hs-1", "ws-1", 5L, T0);
        SessionHarnessBindingRepository bindings = new SessionHarnessBindingRepository() {
            @Override public Optional<SessionHarnessBinding> findByScope(UserId u, SessionId s, long d, int g) {
                return Optional.of(binding);
            }
            @Override public SessionHarnessBinding save(SessionHarnessBinding b) {
                throw new UnsupportedOperationException("测试不应创建新绑定");
            }
        };
        return new SessionBridgeService(bindings, new FakeSnapshots(), new FakeRuns(),
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static final class FakeBundles implements HarnessDefinitionBundleRepository {
        @Override public HarnessDefinitionBundle save(HarnessDefinitionBundle bundle) { return bundle; }
        @Override public Optional<HarnessDefinitionBundle> findByDefinitionVersionId(long definitionVersionId) {
            if (definitionVersionId != 7L) return Optional.empty();
            return Optional.of(new HarnessDefinitionBundle(1L, 7L, "行政助理", "你是行政助理。",
                    "openai-compatible", "test-model", 8, "{}", "w".repeat(64),
                    List.of(new PublishedToolSchema(101L, "meeting-room.search", "meeting_room_search", "查询",
                                    Map.of("type", "object"), true, false, "meeting_room.availability.read"),
                            new PublishedToolSchema(102L, "meeting-room.reserve", "meeting_room_reserve", "预订",
                                    Map.of("type", "object"), false, true, "meeting_room.booking.create")),
                    "c".repeat(64), null, null, "b".repeat(64), T0));
        }
        @Override public Optional<HarnessDefinitionBundle> findByBundleHash(String bundleHash) {
            return Optional.empty();
        }
    }

    private static final class FakeSnapshots implements SessionBridgeSnapshotRepository {
        @Override public Optional<SessionBridgeSnapshot> findById(long id) {
            return Optional.of(new SessionBridgeSnapshot(5L, OWNER, SESSION, 7L, 1, 3,
                    "用户要订明天下午的会议室。", "{}", 1, "h".repeat(64), T0));
        }
        @Override public SessionBridgeSnapshot save(SessionBridgeSnapshot snapshot) { return snapshot; }
    }

    private static final class FakeRuns implements SessionRunStore {
        boolean direct;
        boolean runtimeHistory;
        @Override public boolean hasRuntimeHistory(SessionId session, UserId user, RunId run) {
            return runtimeHistory;
        }
        @Override public AgentSession createSession(AgentSession session) { return session; }
        @Override public Optional<AgentSession> findSession(SessionId sessionId, UserId owner) {
            return Optional.of(new AgentSession(sessionId, owner, 1L, AgentSession.Status.ACTIVE, T0, T0, 0,
                    7L, !direct));
        }
        @Override public AgentRun createRun(AgentRun run, String input, HarnessRunSpec runSpec) { return run; }
        @Override public Optional<AgentRun> findRun(RunId runId, UserId owner) { return Optional.empty(); }
        @Override public List<RunEvent> findEvents(RunId runId, UserId owner, int afterSequence, int limit) {
            return List.of(new RunEvent(runId, 1, "USER_INPUT", "帮我订明天下午的会议室", T0));
        }
    }

    private static final class FakeToolExecutions implements ToolExecutionRepository {
        List<PlatformToolExecution> deliverables = List.of();
        @Override public Optional<PlatformToolExecution> findById(String id) { return Optional.empty(); }
        @Override public Optional<PlatformToolExecution> findByRunAndToolUseId(RunId runId, String toolUseId) {
            return Optional.empty();
        }
        @Override public List<PlatformToolExecution> findByRun(RunId runId) { return List.of(); }
        @Override public List<PlatformToolExecution> findDeliverableResults(RunId runId) { return deliverables; }
        @Override public Optional<com.haizhuo.brain.platform.tool.ClaimedToolExecution> claimNextExecutable(String workerId) {
            return Optional.empty();
        }
        @Override public void completeExecution(PlatformToolExecution outcome) { }
        @Override public void requeueRunIfSettled(RunId runId) { }
    }

    private static final class FakeRealtimeEvents implements RunRealtimeEventPublisher {
        final List<String> textDeltas = new ArrayList<>();
        final List<String> attemptIds = new ArrayList<>();
        final List<Long> streamOffsets = new ArrayList<>();
        boolean failPublish;

        @Override public void publishTextDelta(SessionId sessionId, RunId runId, String attemptId, long streamOffset,
                                               String text, Instant occurredAt) {
            if (failPublish) throw new IllegalStateException("realtime unavailable");
            assertEquals(SESSION, sessionId);
            assertEquals(RUN, runId);
            assertEquals(T0, occurredAt);
            attemptIds.add(attemptId);
            streamOffsets.add(streamOffset);
            textDeltas.add(text);
        }
    }

    private static final class FakeExecutionStore implements RunExecutionStore {
        ExecutionClaim claim = claim(ExecutionResumeReason.INITIAL_PROMPT);
        List<PlatformToolExecution> suspendedExecutions = new ArrayList<>();
        List<ToolApproval> suspendedApprovals = new ArrayList<>();
        List<String> suspendDeliveredIds;
        RunState waitingState;
        String completedResult;
        List<String> completedDeliveredIds;
        String failedCode;
        String failedMessage;
        String cancelledMessage;
        List<String> planSnapshots = new ArrayList<>();
        boolean failPlanSnapshot;

        void reset() {
            suspendedExecutions = new ArrayList<>();
            suspendedApprovals = new ArrayList<>();
            suspendDeliveredIds = null;
            waitingState = null;
            completedResult = null;
            completedDeliveredIds = null;
            failedCode = null;
            failedMessage = null;
            cancelledMessage = null;
            planSnapshots = new ArrayList<>();
            failPlanSnapshot = false;
        }

        @Override public Optional<ExecutionClaim> claimNext(String workerId, Duration leaseTtl) {
            return Optional.ofNullable(claim);
        }
        @Override public boolean heartbeat(String attemptId, String leaseToken, long fenceToken, Duration ttl) {
            return true;
        }
        @Override public boolean suspendForTools(ExecutionClaim c, List<PlatformToolExecution> executions,
                                                 List<ToolApproval> approvals, RunState state,
                                                 List<String> deliveredIds) {
            suspendedExecutions.addAll(executions);
            suspendedApprovals.addAll(approvals);
            waitingState = state;
            suspendDeliveredIds = deliveredIds;
            return true;
        }
        @Override public boolean complete(ExecutionClaim c, String result, List<String> deliveredIds) {
            completedResult = result;
            completedDeliveredIds = deliveredIds;
            return true;
        }
        @Override public boolean fail(ExecutionClaim c, String errorCode, String safeMessage) {
            failedCode = errorCode;
            failedMessage = safeMessage;
            return true;
        }
        @Override public boolean cancelled(ExecutionClaim c, String safeMessage) {
            cancelledMessage = safeMessage;
            return true;
        }
        @Override public boolean recordPlanSnapshot(ExecutionClaim c, String content) {
            if (failPlanSnapshot) {
                throw new IllegalStateException("plan projection unavailable");
            }
            planSnapshots.add(content);
            return true;
        }
        @Override public int reclaimExpiredLeases() { return 0; }
    }

    private static final class FakeChannelTurns implements ChannelTurnPromoter {
        final List<String> promoted = new ArrayList<>();
        boolean fail;

        @Override public void promoteWaitingTurn(SessionId sessionId) {
            if (fail) {
                throw new IllegalStateException("promotion unavailable");
            }
            promoted.add(sessionId.value());
        }
    }

    private static final class FakeChannelReplies implements ChannelReplyEnqueuer {
        final List<String> enqueued = new ArrayList<>();
        boolean fail;

        @Override public void enqueueReply(RunId runId, SessionId sessionId, String text) {
            if (fail) {
                throw new IllegalStateException("outbox unavailable");
            }
            enqueued.add(runId.value() + ":" + text);
        }
    }
}
