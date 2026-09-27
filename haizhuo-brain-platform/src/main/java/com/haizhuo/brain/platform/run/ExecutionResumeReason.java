package com.haizhuo.brain.platform.run;

/** 启动一次执行尝试的原因（规格 §45）：首条用户提示词，或工具结果恢复。 */
public enum ExecutionResumeReason {
    INITIAL_PROMPT,
    TOOL_RESULT_READY
}
