package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ToolApproval;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 认领/租约/心跳/fence 的持久化执行边界（规格 §13.2）。
 * 每个写入方都要校验 attemptId + leaseToken + fenceToken + 尝试状态 RUNNING，
 * 因此过期 worker 永远无法覆盖更新的尝试（I-11/§13.6）。
 */
public interface RunExecutionStore {

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

    /** 置失败尝试与 Run；fence 不匹配时返回 false。 */
    boolean fail(ExecutionClaim claim, String errorCode, String safeMessage);

    /** 确认此前请求的取消；fence 不匹配时返回 false。 */
    boolean cancelled(ExecutionClaim claim, String safeMessage);

    /** 把运行中尝试租约已过期的 Run 重新入队；返回回收数量（§15）。 */
    int reclaimExpiredLeases();
}
