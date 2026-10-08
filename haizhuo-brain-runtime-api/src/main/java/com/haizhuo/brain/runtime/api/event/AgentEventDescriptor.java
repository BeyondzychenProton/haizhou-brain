package com.haizhuo.brain.runtime.api.event;

import java.util.Objects;

/**
 * 原生事件的边界描述，不依赖框架类型，也不包含原始工具入参/思考内容。
 * 缺失字段保留 null；source 不是子实例标识或授权依据。
 */
public record AgentEventDescriptor(String nativeEventId, String nativeCreatedAt, String nativeType,
                                   String nativeSource, String replyId, String blockId, String toolUseId,
                                   String nativeSessionId, String nativeTaskId, String nativeParentSessionId,
                                   AgentExecutionRole executionRole, String attemptId, Long fenceToken) {
    public AgentEventDescriptor {
        Objects.requireNonNull(executionRole, "executionRole");
    }
}
