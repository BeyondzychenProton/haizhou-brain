package com.haizhuo.brain.platform.employee;

import java.util.Map;

/** 能力目录中的一个已登记修订；执行实现必须由运行时白名单提供者承接。 */
public record CapabilityCatalogEntry(String capabilityCode, CapabilityBinding.CapabilityType type,
                                     String revision, String displayName, String description,
                                     String toolName, String implementationKey,
                                     String businessAction, Map<String, Object> inputSchema,
                                     boolean enabled) {
    public CapabilityCatalogEntry {
        inputSchema = Map.copyOf(inputSchema);
    }
}
