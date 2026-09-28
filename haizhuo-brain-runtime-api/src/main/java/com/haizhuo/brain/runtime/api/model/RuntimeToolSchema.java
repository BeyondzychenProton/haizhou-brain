package com.haizhuo.brain.runtime.api.model;

import java.util.Map;
import java.util.Objects;

/**
 * 对运行时可见的已发布工具 Schema。运行时绝不能拿到 implementationKey、凭据
 * 或平台持久化实体（规格 §6.2/§29.2）。
 */
public record RuntimeToolSchema(long capabilityRevisionId, String referenceId, String toolName,
                                String description, Map<String, Object> inputSchema,
                                boolean readOnly, boolean requiresConfirmation, String businessAction) {
    public RuntimeToolSchema {
        if (capabilityRevisionId <= 0) throw new IllegalArgumentException("capabilityRevisionId must be positive");
        Objects.requireNonNull(referenceId);
        Objects.requireNonNull(toolName);
        Objects.requireNonNull(description);
        inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema));
    }
}
