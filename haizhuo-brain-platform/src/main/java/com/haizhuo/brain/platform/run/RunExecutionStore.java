package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ToolApproval;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import com.haizhuo.brain.runtime.api.event.AgentInternalEvent;
import com.haizhuo.brain.runtime.api.DelegationBudgetProvider;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;

/**
 * 认领/租约/心跳/fence 的持久化执行边界（规格 §13.2）。
 * 每个写入方都要校验 attemptId + leaseToken + fenceToken + 尝试状态 RUNNING，
 * 因此过期 worker 永远无法覆盖更新的尝试（I-11/§13.6）。
 */
public interface RunExecutionStore extends DelegationBudgetProvider, DelegationAcceptanceProvider {

    /** Implementations without durable reservation support must fail closed for expert profiles. */
    @Override
    default Reservation reserve(RunId runId, String attemptId, long fenceToken, String roleId,
                                int maxInvocations, int maxParallel) {
        throw new IllegalStateException("durable expert invocation reservations are unavailable");
    }

    @Override
    default List<AcceptedDelegation> acceptBatch(RunId runId, String attemptId, long fenceToken,
                                                 int maxInvocations, int maxParallel,
                                                 List<DelegationCall> calls) {
        throw new IllegalStateException("durable work-item acceptance is unavailable");
    }

    @Override
    default Optional<WorkItemResult> readResult(RunId runId, String attemptId, long fenceToken,
                                                 String workItemRef, int assignmentRevision) {
        return Optional.empty();
    }

    @Override
    default boolean reviewResult(RunId runId, String attemptId, long fenceToken,
                                 String workItemRef, int assignmentRevision, String resultId,
                                 String bodySha256, String contractVersion,
                                 ReviewDecision decision, String reason) {
        return false;
    }

    /** Exact immutable results accepted as user references for this Run. */
    default List<AgentResult> findReferencedResults(RunId runId, UserId owner) { return List.of(); }

    /** 认领最早入队的 Run，创建下一个尝试并颁发新租约与新 fence（Tx-03）。 */
    Optional<ExecutionClaim> claimNext(String workerId, Duration leaseTtl);

    /** 续约；租约已丢失时返回 false（§14.2）。 */
    boolean heartbeat(String attemptId, String leaseToken, long fenceToken, Duration leaseTtl);

    /**
     * 为外部工具执行挂起该尝试与 Run（Tx-04）；fence 不匹配时返回 false。
     * deliveredToolExecutionIds 会把本次尝试已消费的恢复结果的 READY→DELIVERED 一次性守卫翻转
     * （§45.1）——挂起意味着 Harness 已经接收了它们，下次认领时不得再次投递。
     */
    boolean suspendForTools(ExecutionClaim claim, List<PlatformToolExecution> executions,
                            List<ToolApproval> approvals, RunState waitingState,
                            List<String> deliveredToolExecutionIds);

    /** 结束尝试与 Run（Tx-06）；fence 不匹配时返回 false。 */
    boolean complete(ExecutionClaim claim, String result, List<String> deliveredToolExecutionIds);

    default RunCompletion.Status completeFinal(ExecutionClaim claim, RunCompletion completion) {
        return complete(claim, completion.body(), completion.consumedToolIds())
                ? RunCompletion.Status.COMMITTED : RunCompletion.Status.STALE;
    }

    default boolean recordPlanSnapshot(ExecutionClaim claim, String content, AgentEventDescriptor descriptor) {
        return recordPlanSnapshot(claim, content);
    }

    default boolean recordInternalEvent(ExecutionClaim claim, AgentInternalEvent event) { return false; }

    /** Persist a completed fixed-expert result and its internal event under the current Run fence. */
    default Optional<String> recordDelegationResult(ExecutionClaim claim, AgentDelegationResult result) {
        return Optional.empty();
    }

    /** 置失败尝试与 Run；fence 不匹配时返回 false。 */
    boolean fail(ExecutionClaim claim, String errorCode, String safeMessage);

    /** 确认此前请求的取消；fence 不匹配时返回 false。 */
    boolean cancelled(ExecutionClaim claim, String safeMessage);

    /** 对无法确认 Harness 是否已完成副作用的非 legacy Run 进行隔离。 */
    default boolean markRecoveryRequired(ExecutionClaim claim, String reasonCode, String safeMessage) {
        return false;
    }

    /**
     * 运行中记录一次计划快照（P2）：与 run 事件同一事务写入，并同步投影到会话游标，
     * 因此计划在断线后可由会话流恢复。只校验租约与 fence、不改尝试状态——
     * 过期 worker 不得把计划写进已被重新认领的运行。fence 不匹配时返回 false。
     */
    boolean recordPlanSnapshot(ExecutionClaim claim, String content);

    /** 把运行中尝试租约已过期的 Run 重新入队；返回回收数量（§15）。 */
    int reclaimExpiredLeases();
}
