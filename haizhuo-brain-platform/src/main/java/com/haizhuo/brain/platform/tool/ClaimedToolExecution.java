package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.platform.run.AgentRun;
import java.util.Objects;

/**
 * 工具 worker 认领到的一个可执行工具工作项，并已关联出其所属 Run，
 * 因此授权始终使用冻结的 Run 属主（I-14），而不是模型或调用方提供的任何身份。
 */
public record ClaimedToolExecution(PlatformToolExecution execution, AgentRun run) {
    public ClaimedToolExecution {
        Objects.requireNonNull(execution);
        Objects.requireNonNull(run);
    }
}
