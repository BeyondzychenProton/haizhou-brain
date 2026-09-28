package com.haizhuo.brain.runtime.api.event;

import java.util.Map;
import java.util.Objects;

/** 被 Harness 挂起、等待平台执行的一个外部工具调用（规格 §33.1）。 */
public record PendingExternalToolCall(String toolUseId, String toolName, Map<String, Object> arguments) {
    public PendingExternalToolCall {
        Objects.requireNonNull(toolUseId);
        Objects.requireNonNull(toolName);
        arguments = Map.copyOf(Objects.requireNonNull(arguments));
    }
}
