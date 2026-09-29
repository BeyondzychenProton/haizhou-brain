package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.runtime.api.RunObservationSink;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 持久化工具执行循环（规格 §36/§44）：认领一条可执行记录，跑完固定授权流水线，
 * 然后要么停靠等待审批（边缘路径，§39），要么执行并原子落定结果（Tx-05）。
 * worker 绝不在 ToolExecutionRepository.completeExecution 之外写入结果，
 * 该方法会在落定后重新入队 Run。诊断信息体现在持久化的执行状态里；
 * platform 模块不引入日志框架。
 */
public class ToolExecutionWorker {

    private final ToolExecutionRepository executions;
    private final ToolApprovalRepository approvals;
    private final ToolExecutionGatewayService gateway;
    private final Clock clock;
    private final RunObservationSink observationSink;

    public ToolExecutionWorker(ToolExecutionRepository executions, ToolApprovalRepository approvals,
                               ToolExecutionGatewayService gateway, Clock clock) {
        this(executions, approvals, gateway, clock, RunObservationSink.noop());
    }

    public ToolExecutionWorker(ToolExecutionRepository executions, ToolApprovalRepository approvals,
                               ToolExecutionGatewayService gateway, Clock clock,
                               RunObservationSink observationSink) {
        this.executions = Objects.requireNonNull(executions);
        this.approvals = Objects.requireNonNull(approvals);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
        this.observationSink = Objects.requireNonNull(observationSink);
    }

    /** 认领并落定至多一条工具执行；没有可执行记录时返回 false。 */
    public boolean executeNext(String workerId) {
        Optional<ClaimedToolExecution> claimed = executions.claimNextExecutable(workerId);
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedToolExecution claim = claimed.get();
        PlatformToolExecution execution = claim.execution();
        var startedAt = clock.instant();
        try {
            ToolPreparation preparation = gateway.prepare(execution, claim.run());
            switch (preparation.outcome()) {
                case DENIED -> {
                    PlatformToolExecution settled = outcome(execution, ToolExecutionState.DENIED,
                            false, preparation.safeMessage(), preparation.denialCode(), null);
                    executions.completeExecution(settled);
                    observe(claim, settled.state().name(), settled.errorCode(), startedAt);
                }
                case APPROVAL_REQUIRED -> {
                    approvals.createPending(execution, claim.run().userId().value());
                    observe(claim, ToolExecutionState.APPROVAL_REQUIRED.name(), null, startedAt);
                }
                case ALLOWED -> {
                    ToolExecutionResult result = gateway.execute(execution, claim.run(), preparation);
                    PlatformToolExecution settled = outcomeOf(execution, result);
                    executions.completeExecution(settled);
                    observe(claim, settled.state().name(), settled.errorCode(), startedAt);
                }
            }
        } catch (RuntimeException error) {
            // Tx-05 仍会把该记录原子落定；FAILED 状态承载执行结果。
            PlatformToolExecution settled = outcome(execution, ToolExecutionState.FAILED,
                    false, "操作执行失败，请稍后重试。", "TOOL_WORKER_ERROR", null);
            executions.completeExecution(settled);
            observe(claim, settled.state().name(), settled.errorCode(), startedAt);
        }
        return true;
    }

    private void observe(ClaimedToolExecution claim, String state, String errorCode,
                         java.time.Instant startedAt) {
        try {
            PlatformToolExecution execution = claim.execution();
            observationSink.onToolExecution(execution.runId().value(), claim.run().sessionId().value(),
                    Long.toString(claim.run().userId().value()), execution.id(), execution.toolName(),
                    execution.capabilityRevisionId(), state, errorCode, execution.inputDigest(),
                    startedAt, clock.instant());
        } catch (RuntimeException ignored) {
            // 观测系统故障不得回滚或改变已落定的工具结果。
        }
    }

    private PlatformToolExecution outcomeOf(PlatformToolExecution execution, ToolExecutionResult result) {
        if (result.reconciliationRequired()) {
            // §43.4：副作用可能已经离开平台、结果未知——绝不盲目重试。
            return outcome(execution, ToolExecutionState.RECONCILIATION_REQUIRED,
                    false, result.content(), result.errorCode(), null);
        }
        if (result.success()) {
            return outcome(execution, ToolExecutionState.SUCCEEDED, true, result.content(), null,
                    result.externalReceipt());
        }
        return outcome(execution, ToolExecutionState.FAILED, false, result.content(), result.errorCode(), null);
    }

    private PlatformToolExecution outcome(PlatformToolExecution execution, ToolExecutionState state,
                                          boolean success, String content, String errorCode,
                                          String externalReceipt) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", success);
        envelope.put("content", content);
        String resultJson = CanonicalJson.write(envelope);
        var now = clock.instant();
        return new PlatformToolExecution(execution.id(), execution.runId(), execution.toolUseId(),
                execution.capabilityRevisionId(), execution.toolName(), execution.inputJson(),
                execution.inputDigest(), state, execution.idempotencyKey(), externalReceipt,
                resultJson, CanonicalJson.sha256Hex(resultJson.getBytes(StandardCharsets.UTF_8)),
                ResultDeliveryState.READY, errorCode, execution.createdAt(), now);
    }
}
