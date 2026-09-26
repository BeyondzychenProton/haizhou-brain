package com.haizhuo.brain.runtime.api.model;

import java.util.Map;

/** Exact capability revision allowed for this Run. */
public record RuntimeCapability(String type, String referenceId, String revision, String toolName,
                                String description, String implementationKey, String businessAction,
                                Map<String, Object> inputSchema) {
    public RuntimeCapability {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }

    /** 保留旧调用点的构造方式；生产装配只接受包含受控实现元数据的完整 DTO。 */
    public RuntimeCapability(String type, String referenceId, String revision) {
        this(type, referenceId, revision, null, null, null, null, Map.of());
    }

    public RuntimeCapability(String type, String referenceId, String revision, String toolName,
                             String description, String implementationKey, Map<String, Object> inputSchema) {
        this(type, referenceId, revision, toolName, description, implementationKey, null, inputSchema);
    }
}
