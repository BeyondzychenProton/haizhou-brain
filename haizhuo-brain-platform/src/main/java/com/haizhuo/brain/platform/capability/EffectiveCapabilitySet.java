package com.haizhuo.brain.platform.capability;

import com.haizhuo.brain.runtime.api.model.RuntimeCapability;
import java.time.Instant;
import java.util.List;

/** 服务端按发布版本和当前主体权限计算出的单次 Run 能力快照。 */
public record EffectiveCapabilitySet(String runId, long definitionVersionId, long userId,
                                     String snapshotHash, List<RuntimeCapability> allowedCapabilities,
                                     List<CapabilityExclusion> exclusions, Instant resolvedAt) {
    public EffectiveCapabilitySet {
        allowedCapabilities = List.copyOf(allowedCapabilities);
        exclusions = List.copyOf(exclusions);
    }
}
