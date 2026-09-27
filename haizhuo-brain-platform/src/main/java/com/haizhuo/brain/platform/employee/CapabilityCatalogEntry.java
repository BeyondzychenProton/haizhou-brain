package com.haizhuo.brain.platform.employee;

import java.util.Map;

/**
 * 能力目录中的一个已登记修订；执行实现必须由运行时白名单提供者承接。
 * capabilityRevisionId 是发布物与 ToolExecution 引用的稳定数值身份（spec §6.2）；
 * implementationKey 只留在平台侧，从不下发给 Runtime。
 */
public record CapabilityCatalogEntry(long capabilityRevisionId, String capabilityCode,
                                     CapabilityBinding.CapabilityType type,
                                     String revision, String displayName, String description,
                                     String toolName, String implementationKey,
                                     String businessAction, Map<String, Object> inputSchema,
                                     boolean enabled, boolean requiresConfirmation) {
    public CapabilityCatalogEntry {
        inputSchema = Map.copyOf(inputSchema);
    }
}
