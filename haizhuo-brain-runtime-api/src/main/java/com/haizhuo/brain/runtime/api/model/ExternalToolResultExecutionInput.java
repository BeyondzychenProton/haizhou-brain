package com.haizhuo.brain.runtime.api.model;

import java.util.List;
import java.util.Objects;

/** 投递给已挂起 Harness 调用、用于恢复执行的工具结果（规格 §7.5/§32）。 */
public record ExternalToolResultExecutionInput(List<ExternalToolResult> results)
        implements AgentExecutionInput {
    public ExternalToolResultExecutionInput {
        results = List.copyOf(Objects.requireNonNull(results));
        if (results.isEmpty()) throw new IllegalArgumentException("results must not be empty");
    }
}
