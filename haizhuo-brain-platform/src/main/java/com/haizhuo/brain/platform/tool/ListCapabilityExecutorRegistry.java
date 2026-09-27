package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import java.util.List;
import java.util.Objects;

/**
 * 显式白名单注册表（规格 §42.1）：只有在接线时注册过的执行器才能解析某个
 * implementationKey。数据库中的值永远不会变成类加载或脚本执行的入口。
 */
public class ListCapabilityExecutorRegistry implements CapabilityExecutorRegistry {

    private final List<CapabilityExecutor> executors;

    public ListCapabilityExecutorRegistry(List<CapabilityExecutor> executors) {
        this.executors = List.copyOf(Objects.requireNonNull(executors));
    }

    @Override
    public boolean supports(String implementationKey, String capabilityCode) {
        return executors.stream().anyMatch(executor -> executor.implementationKey().equals(implementationKey));
    }

    @Override
    public CapabilityExecutor resolve(CapabilityCatalogEntry capability) {
        return executors.stream()
                .filter(executor -> executor.implementationKey().equals(capability.implementationKey())
                        && executor.supports(capability))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No executor registered for implementationKey: " + capability.implementationKey()));
    }
}
