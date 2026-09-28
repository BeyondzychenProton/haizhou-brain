package com.haizhuo.brain.runtime.api.model;

import java.util.Objects;

/**
 * 平台侧给出的、用于恢复执行的工具结果（规格 §7.5）。content 是安全且限长的摘要，
 * 供应商原始报文与凭据绝不能进入模型。
 */
public record ExternalToolResult(String toolUseId, String toolName, boolean success, String content) {
    public ExternalToolResult {
        Objects.requireNonNull(toolUseId);
        Objects.requireNonNull(toolName);
        Objects.requireNonNull(content);
    }
}
