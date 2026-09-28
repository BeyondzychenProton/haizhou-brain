package com.haizhuo.brain.platform.mcp;

import java.util.Map;
import java.util.Objects;

/** 从单个用户的 tools/list 观察到的工具声明；观察结果不等于批准。 */
public record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema,
                                Map<String, Object> outputSchema, boolean readOnlyHint) {
    public McpToolDescriptor {
        Objects.requireNonNull(name);
        description = description == null ? "" : description;
        inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema));
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
    }
}
