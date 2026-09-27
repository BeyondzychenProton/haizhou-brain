package com.haizhuo.brain.platform.tool;

import java.util.Objects;

/**
 * 执行器结果。content 是给模型的安全、限长摘要；
 * 供应商原始报文必须先被精简才能进入这条记录（规格 §37/§89）。
 */
public record ToolExecutionResult(boolean success, String content, String errorCode,
                                  String externalReceipt, boolean reconciliationRequired) {
    public ToolExecutionResult {
        Objects.requireNonNull(content);
    }

    public static ToolExecutionResult succeeded(String content, String externalReceipt) {
        return new ToolExecutionResult(true, content, null, externalReceipt, false);
    }

    public static ToolExecutionResult failed(String errorCode, String safeMessage) {
        return new ToolExecutionResult(false, safeMessage, errorCode, null, false);
    }

    public static ToolExecutionResult unknown(String safeMessage) {
        return new ToolExecutionResult(false, safeMessage, "TOOL_RESULT_UNKNOWN", null, true);
    }
}
