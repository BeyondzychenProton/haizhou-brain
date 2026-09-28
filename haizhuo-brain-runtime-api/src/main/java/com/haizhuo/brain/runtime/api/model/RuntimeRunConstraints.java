package com.haizhuo.brain.runtime.api.model;

import java.util.Objects;
import java.util.Set;

/**
 * Run 级冻结的工具视图（规格 §7.3/§11）。Run 创建之后可见范围只能收窄、不能放宽；
 * 执行授权始终留在平台网关侧。
 */
public record RuntimeRunConstraints(String toolViewHash, Set<String> modelVisibleToolNames,
                                    String effectiveCapabilityHash) {
    public RuntimeRunConstraints {
        Objects.requireNonNull(toolViewHash);
        modelVisibleToolNames = Set.copyOf(Objects.requireNonNull(modelVisibleToolNames));
        Objects.requireNonNull(effectiveCapabilityHash);
    }
}
