package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.channel.ChannelReplyEnqueuer;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundle;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleRepository;
import com.haizhuo.brain.platform.employee.runtime.PublishedToolSchema;
import com.haizhuo.brain.platform.harness.SessionBridgeService;
import com.haizhuo.brain.platform.harness.SessionBridgeSnapshot;
import com.haizhuo.brain.platform.harness.SessionBridgeSnapshotRepository;
import com.haizhuo.brain.platform.harness.SessionHarnessBinding;
import com.haizhuo.brain.platform.tool.ApprovalState;
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
import com.haizhuo.brain.runtime.api.model.AgentExecutionInput;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.ExternalToolResult;
import com.haizhuo.brain.runtime.api.model.ExternalToolResultExecutionInput;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeRunConstraints;
import com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding;
import com.haizhuo.brain.runtime.api.model.RuntimeToolSchema;
import com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 持久化 Run 执行循环（规格 §13/§44-§46）：带租约与 fence 地认领，构建冻结的
 * AgentExecutionRequest（§7.6），驱动运行时，然后只落定唯一一个持久结果——
 * 挂起等工具、完成、取消或失败。运行时本身不触碰数据库；本服务是唯一的写入方。
 * 恢复结果只在其投递状态为 READY 时才进入，这就是平台侧的重复恢复防护（§46）。
 * 诊断信息落在持久化的 run/attempt 状态与事件里；platform 模块不引入日志框架。
 */
public class RunExecutionService {

    /** P0：租户模型落地之前按单租户部署。 */
    private static final long DEFAULT_TENANT = 1L;
    /** 只解析平台自己写入的 {success, content} 结果信封；绝不解析工具入参。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final RunExecutionStore executionStore;
    private final HarnessDefinitionBundleRepository bundles;
    private final SessionBridgeService bridgeService;
    private final SessionBridgeSnapshotRepository snapshots;
    private final SessionRunStore runs;
    private final ToolExecutionRepository toolExecutions;
    private final AgentRuntime runtime;
    private final RunRealtimeEventPublisher realtimeEvents;
    private final ChannelReplyEnqueuer replies;
    private final Clock clock;
    private final ScheduledExecutorService heartbeats;

    public RunExecutionService(RunExecutionStore executionStore, HarnessDefinitionBundleRepository bundles,
                               SessionBridgeService bridgeService, SessionBridgeSnapshotRepository snapshots,
                               SessionRunStore runs, ToolExecutionRepository toolExecutions,
                               AgentRuntime runtime, RunRealtimeEventPublisher realtimeEvents,
                               ChannelReplyEnqueuer replies, Clock clock) {
        this.executionStore = Objects.requireNonNull(executionStore);
        this.bundles = Objects.requireNonNull(bundles);
        this.bridgeService = Objects.requireNonNull(bridgeService);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.runs = Objects.requireNonNull(runs);
        this.toolExecutions = Objects.requireNonNull(toolExecutions);
        this.runtime = Objects.requireNonNull(runtime);
        this.realtimeEvents = Objects.requireNonNull(realtimeEvents);
        this.replies = Objects.requireNonNull(replies);
        this.clock = Objects.requireNonNull(clock);
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "run-execution-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 认领并驱动至多一个 Run；没有排队中的 Run 时返回 false。 */
    public boolean executeNext(String workerId, Duration leaseTtl) {
        Optional<ExecutionClaim> claimed = executionStore.claimNext(workerId, leaseTtl);
        if (claimed.isEmpty()) {
            return false;
        }
        ExecutionClaim claim = claimed.get();
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(claim, leaseTtl);
        try {
            settle(claim, drive(claim));
        } catch (RuntimeException error) {
            // 持久化的 FAILED 状态承载该结果；fence token 会拒绝任何过期写入。
            executionStore.fail(claim, "RUN_EXECUTION_ERROR", "运行执行失败，请稍后重试。");
        } finally {
            heartbeat.cancel(false);
        }
        return true;
    }

    private record DrivenRun(ExecutionClaim claim, AgentExecutionRequest request,
                             List<String> deliverableIds, AgentToolSuspendedEvent suspended,
                             AgentRunCompletedEvent completed, AgentRunCancelledEvent cancelled,
                             AgentRunFailedEvent failed) {
    }

    /** 构建冻结请求，并把运行时事件流收敛为恰好一个终止事件。 */
    private DrivenRun drive(ExecutionClaim claim) {
        List<PlatformToolExecution> deliverables = claim.resumeReason() == ExecutionResumeReason.TOOL_RESULT_READY
                ? toolExecutions.findDeliverableResults(claim.run().id())
                : List.of();
        AgentExecutionRequest request = buildRequest(claim, deliverables);

        AtomicReference<AgentToolSuspendedEvent> suspended = new AtomicReference<>();
        AtomicReference<AgentRunCompletedEvent> completed = new AtomicReference<>();
        AtomicReference<AgentRunCancelledEvent> cancelled = new AtomicReference<>();
        AtomicReference<AgentRunFailedEvent> failed = new AtomicReference<>();
        AtomicLong streamOffset = new AtomicLong();
        runtime.execute(request).doOnNext(event -> {
                    if (event instanceof AgentTextDeltaEvent delta) {
                        publishTextDelta(claim.run().sessionId(), claim.run().id(), claim.attempt().attemptId(),
                                streamOffset.incrementAndGet(), delta.text());
                    }
                    if (event instanceof AgentPlanUpdatedEvent plan) {
                        recordPlanSnapshot(claim, plan);
                    }
                    classify(event, suspended, completed, cancelled, failed);
                })
                .blockLast();
        return new DrivenRun(claim, request, deliverables.stream().map(PlatformToolExecution::id).toList(),
                suspended.get(), completed.get(), cancelled.get(), failed.get());
    }

    private void settle(ExecutionClaim claim, DrivenRun driven) {
        if (driven.suspended() != null) {
            suspend(claim, driven);
            return;
        }
        if (driven.completed() != null) {
            executionStore.complete(claim, driven.completed().result(), driven.deliverableIds());
            enqueueChannelReply(claim, driven.completed().result());
            return;
        }
        if (driven.cancelled() != null) {
            executionStore.cancelled(claim, driven.cancelled().message());
            return;
        }
        if (driven.failed() != null) {
            executionStore.fail(claim, "AGENT_RUNTIME", driven.failed().message());
            return;
        }
        executionStore.fail(claim, "RUNTIME_EMPTY", "运行未产生结果，请稍后重试。");
    }

    /** Tx-04 载荷：由挂起调用冻结出的持久化工具执行（及审批）。 */
    private void suspend(ExecutionClaim claim, DrivenRun driven) {
        AgentToolSuspendedEvent suspended = driven.suspended();
        HarnessDefinitionBundle bundle = bundles.findByDefinitionVersionId(claim.run().definitionVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Runtime bundle missing for definition version " + claim.run().definitionVersionId()));
        Map<String, PublishedToolSchema> catalogByName = new LinkedHashMap<>();
        for (PublishedToolSchema tool : bundle.toolCatalog()) {
            catalogByName.put(tool.toolName(), tool);
        }

        Instant now = clock.instant();
        List<PlatformToolExecution> executions = new ArrayList<>();
        List<ToolApproval> approvals = new ArrayList<>();
        for (PendingExternalToolCall call : suspended.toolCalls()) {
            PublishedToolSchema tool = catalogByName.get(call.toolName());
            if (tool == null) {
                // 模型调用了冻结目录之外的东西；拒绝落库。
                executionStore.fail(claim, "TOOL_OUT_OF_CATALOG", "模型请求了目录之外的工具。");
                return;
            }
            String inputJson = CanonicalJson.write(call.arguments());
            String inputDigest = CanonicalJson.sha256Hex(inputJson.getBytes(StandardCharsets.UTF_8));
            // §43.1：幂等键 = SHA-256(runId + toolUseId + capabilityRevisionId + inputDigest)
            String idempotencyKey = CanonicalJson.sha256Hex((claim.run().id().value() + "|" + call.toolUseId()
                    + "|" + tool.capabilityRevisionId() + "|" + inputDigest).getBytes(StandardCharsets.UTF_8));
            boolean needsConfirmation = tool.requiresConfirmation();
            String executionId = UUID.randomUUID().toString();
            executions.add(new PlatformToolExecution(executionId, claim.run().id(), call.toolUseId(),
                    tool.capabilityRevisionId(), call.toolName(), inputJson, inputDigest,
                    needsConfirmation ? ToolExecutionState.APPROVAL_REQUIRED : ToolExecutionState.REQUESTED,
                    idempotencyKey, null, null, null, null, null, now, now));
            if (needsConfirmation) {
                approvals.add(new ToolApproval(UUID.randomUUID().toString(), executionId, claim.run().id(),
                        claim.run().userId(), ApprovalState.PENDING, null, null, now, null));
            }
        }
        RunState waitingState = approvals.isEmpty() ? RunState.WAITING_TOOL : RunState.WAITING_CONFIRMATION;
        executionStore.suspendForTools(claim, executions, approvals, waitingState, driven.deliverableIds());
    }

    private AgentExecutionRequest buildRequest(ExecutionClaim claim, List<PlatformToolExecution> deliverables) {
        AgentRun run = claim.run();
        HarnessDefinitionBundle bundle = bundles.findByDefinitionVersionId(run.definitionVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Runtime bundle missing for definition version " + run.definitionVersionId()));
        SessionHarnessBinding binding = bridgeService.getOrCreateBinding(
                run.userId(), run.sessionId(), run.employeeId(), run.definitionVersionId());
        SessionBridgeSnapshot snapshot = snapshots.findById(binding.bridgeSnapshotId())
                .orElseThrow(() -> new IllegalStateException("Bridge snapshot missing: " + binding.bridgeSnapshotId()));

        RuntimeDefinitionSnapshot definition = new RuntimeDefinitionSnapshot(
                bundle.definitionVersionId(), bundle.employeeName(), bundle.instructions(),
                bundle.modelProvider(), bundle.modelName(), bundle.maxIterations(), bundle.bundleHash(),
                "defws:" + bundle.bundleHash(), bundle.workspaceContentHash(),
                bundle.toolCatalog().stream()
                        .map(tool -> new RuntimeToolSchema(tool.capabilityRevisionId(), tool.capabilityReferenceId(),
                                tool.toolName(), tool.description(), tool.inputSchema(), tool.readOnly(),
                                tool.requiresConfirmation(), tool.businessAction()))
                        .toList());
        RuntimeRunConstraints constraints = new RuntimeRunConstraints(claim.runSpec().toolViewHash(),
                claim.runSpec().modelVisibleToolNames(), claim.runSpec().effectiveCapabilityHash());
        RuntimeSessionBinding sessionBinding = new RuntimeSessionBinding(binding.harnessSessionKey(),
                binding.workspaceRuntimeKey(), snapshot.contentHash(), snapshot.conversationSummary());
        AgentExecutionInput input = claim.resumeReason() == ExecutionResumeReason.TOOL_RESULT_READY
                ? new ExternalToolResultExecutionInput(deliverables.stream().map(this::toToolResult).toList())
                : new UserPromptExecutionInput(initialPrompt(run));
        return new AgentExecutionRequest(new TenantId(DEFAULT_TENANT), run.userId(), run.sessionId(), run.id(),
                TraceId.newId(), claim.attempt().attemptId(), claim.attempt().fenceToken(),
                definition, constraints, sessionBinding, input);
    }

    /** 每个 Run 的首个事件都是创建时写入的冻结 USER_INPUT（§10.2）。 */
    private String initialPrompt(AgentRun run) {
        return runs.findEvents(run.id(), run.userId(), 0, 1).stream()
                .filter(event -> "USER_INPUT".equals(event.type()))
                .findFirst()
                .map(RunEvent::content)
                .orElseThrow(() -> new IllegalStateException("Initial USER_INPUT event missing for run " + run.id().value()));
    }

    private ExternalToolResult toToolResult(PlatformToolExecution execution) {
        boolean success = execution.state() == ToolExecutionState.SUCCEEDED;
        return new ExternalToolResult(execution.toolUseId(), execution.toolName(), success,
                safeContent(execution, success));
    }

    /** result_json 承载 {success, content}；content 已经是安全、限长的摘要。 */
    private String safeContent(PlatformToolExecution execution, boolean success) {
        String resultJson = execution.resultJson();
        if (resultJson == null || resultJson.isBlank()) {
            return success ? "工具调用已完成。" : "工具调用未完成。";
        }
        try {
            var node = JSON.readTree(resultJson);
            var content = node.get("content");
            return content == null || content.isNull() ? (success ? "工具调用已完成。" : "工具调用未完成。")
                    : content.asText();
        } catch (Exception error) {
            return success ? "工具调用已完成。" : "工具调用未完成。";
        }
    }

    private void publishTextDelta(SessionId sessionId, RunId runId, String attemptId, long streamOffset, String text) {
        try {
            realtimeEvents.publishTextDelta(sessionId, runId, attemptId, streamOffset, text, clock.instant());
        } catch (RuntimeException ignored) {
            // 实时投递是可降级展示链路，不能反向改变 Run 的业务结果。
        }
    }

    private void classify(BrainAgentEvent event, AtomicReference<AgentToolSuspendedEvent> suspended,
                          AtomicReference<AgentRunCompletedEvent> completed,
                          AtomicReference<AgentRunCancelledEvent> cancelled,
                          AtomicReference<AgentRunFailedEvent> failed) {
        if (event instanceof AgentToolSuspendedEvent toolSuspended) {
            suspended.set(toolSuspended);
        } else if (event instanceof AgentRunCompletedEvent runCompleted) {
            completed.set(runCompleted);
        } else if (event instanceof AgentRunCancelledEvent runCancelled) {
            cancelled.set(runCancelled);
        } else if (event instanceof AgentRunFailedEvent runFailed) {
            failed.set(runFailed);
        }
        // AgentTextDeltaEvent 只经实时端口投递，不落 token 增量；最终答案文本仍由
        // RUN_COMPLETED 持久事件承载并用于断线后的整体校准。
    }

    /**
     * 计划快照（P2）：只有带正文的写入才落库——ENTER/EXIT 只表达计划模式的开关，
     * 没有可展示的内容。计划属于可降级展示链路，落库失败不得影响 Run 的业务结果。
     */
    private void recordPlanSnapshot(ExecutionClaim claim, AgentPlanUpdatedEvent plan) {
        if (plan.phase() != AgentPlanUpdatedEvent.Phase.WRITE
                || plan.plan() == null || plan.plan().isBlank()) {
            return;
        }
        try {
            executionStore.recordPlanSnapshot(claim, plan.plan());
        } catch (RuntimeException ignored) {
            // 与实时投递一致：投递/投影失败只影响展示，不反向改写业务终态。
        }
    }

    /**
     * 渠道回投（P3）：Run 有了最终答复时，若该会话来自渠道则入队一条投递。
     * 与实时链路一致，入队失败不回写已经落定的 Run 终态。
     */
    private void enqueueChannelReply(ExecutionClaim claim, String result) {
        try {
            replies.enqueueReply(claim.run().id(), claim.run().sessionId(), result);
        } catch (RuntimeException ignored) {
            // 渠道回投可降级：投递失败由 outbox 的重试与人工核查兜底。
        }
    }

    private ScheduledFuture<?> scheduleHeartbeat(ExecutionClaim claim, Duration leaseTtl) {
        long intervalMs = Math.max(1000, leaseTtl.toMillis() / 3);
        return heartbeats.scheduleWithFixedDelay(() -> {
            try {
                // 返回 false 意味着租约已丢失（被回收）；fence token 随后会拒绝
                // 该 worker 的落定写入，因此这里无需再做处理。
                executionStore.heartbeat(claim.attempt().attemptId(),
                        claim.attempt().leaseToken(), claim.attempt().fenceToken(), leaseTtl);
            } catch (RuntimeException ignored) {
                // 下一个 tick 会重试；持续故障会让租约过期，由 reaper 重新入队。
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }
}
