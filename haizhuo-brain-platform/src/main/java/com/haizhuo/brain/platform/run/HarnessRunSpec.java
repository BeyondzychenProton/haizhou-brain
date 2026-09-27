package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Run 创建时冻结的、Run 级不可变事实（规格 §6.3）。插入后永不更新。
 * modelVisibleToolNames 只决定模型能看到什么——不决定执行授权。
 */
public record HarnessRunSpec(RunId runId, long definitionVersionId, long definitionBundleId,
                             String definitionBundleHash, String modelVisibleToolNamesJson,
                             String toolViewHash, String effectiveCapabilityHash,
                             String channelType, Instant createdAt) {
    public HarnessRunSpec {
        Objects.requireNonNull(runId);
        if (definitionVersionId <= 0 || definitionBundleId <= 0)
            throw new IllegalArgumentException("definition version and bundle are required");
        Objects.requireNonNull(definitionBundleHash);
        Objects.requireNonNull(modelVisibleToolNamesJson);
        Objects.requireNonNull(toolViewHash);
        Objects.requireNonNull(effectiveCapabilityHash);
        Objects.requireNonNull(createdAt);
    }

    public Set<String> modelVisibleToolNames() {
        return new TreeSet<>(CanonicalJson.readStringArray(modelVisibleToolNamesJson));
    }

    public static String toolNamesJson(List<String> toolNames) {
        return CanonicalJson.write(toolNames.stream().sorted().toList());
    }
}
