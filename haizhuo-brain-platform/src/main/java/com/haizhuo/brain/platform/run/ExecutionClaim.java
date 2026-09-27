package com.haizhuo.brain.platform.run;

import java.util.Objects;

/** 原子认领结果：冻结的 Run、其 RunSpec，以及持有租约的新尝试（规格 §13.3）。 */
public record ExecutionClaim(AgentRun run, HarnessRunSpec runSpec, RunExecutionAttempt attempt,
                             ExecutionResumeReason resumeReason) {
    public ExecutionClaim {
        Objects.requireNonNull(run);
        Objects.requireNonNull(runSpec);
        Objects.requireNonNull(attempt);
        Objects.requireNonNull(resumeReason);
    }
}
