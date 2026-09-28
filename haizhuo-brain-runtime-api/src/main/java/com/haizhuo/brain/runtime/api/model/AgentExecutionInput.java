package com.haizhuo.brain.runtime.api.model;

/** 单次 Harness 调用的执行输入（规格 §7.5）。 */
public sealed interface AgentExecutionInput
        permits UserPromptExecutionInput, ExternalToolResultExecutionInput {
}
