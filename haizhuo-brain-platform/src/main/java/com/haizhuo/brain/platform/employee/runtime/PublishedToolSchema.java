package com.haizhuo.brain.platform.employee.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * 发布时冻结进 HarnessDefinitionBundle 的工具 Schema（规格 §6.2）。
 * implementationKey 留在平台能力修订中，绝不下发给运行时。
 */
public record PublishedToolSchema(long capabilityRevisionId, String capabilityReferenceId, String toolName,
                                  String description, Map<String, Object> inputSchema,
                                  boolean readOnly, boolean requiresConfirmation, String businessAction) {
    public PublishedToolSchema {
        if (capabilityRevisionId <= 0) throw new IllegalArgumentException("capabilityRevisionId must be positive");
        Objects.requireNonNull(capabilityReferenceId);
        Objects.requireNonNull(toolName);
        Objects.requireNonNull(description);
        inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema));
    }
}
