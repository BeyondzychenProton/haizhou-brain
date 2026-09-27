package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.kernel.identity.RunId;
import java.util.List;
import java.util.Optional;

/** 持久化工具执行的边界（规格 §57.4）。 */
public interface ToolExecutionRepository {
    Optional<PlatformToolExecution> findById(String toolExecutionId);

    Optional<PlatformToolExecution> findByRunAndToolUseId(RunId runId, String toolUseId);

    List<PlatformToolExecution> findByRun(RunId runId);

    /** 结果已就绪、可一次性投递给 Harness 的终态执行（§45）。 */
    List<PlatformToolExecution> findDeliverableResults(RunId runId);

    /**
     * 为工具 worker 认领最早的可执行记录（REQUESTED 或 APPROVED），
     * 并关联出其所属 Run，使网关可以针对冻结的 Run 属主做授权。
     */
    Optional<ClaimedToolExecution> claimNextExecutable(String workerId);

    /**
     * 原子落定执行结果：终态 + 结果 + 投递状态 READY，
     * 发出工具事件，并在没有未决执行之后重新入队该 Run（Tx-05）。
     */
    void completeExecution(PlatformToolExecution outcome);

    /** 把执行被取消/拒绝的 Run 重新入队，让 Harness 能够收尾（§47/§39.3）。 */
    void requeueRunIfSettled(RunId runId);
}
