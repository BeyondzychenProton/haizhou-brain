package com.haizhuo.brain.platform.employee;

import java.time.Instant;
import java.util.Objects;

/** 修订历史列表的只读摘要；不包含原因、请求标识或文件正文。 */
public record CapabilityAssetRevisionSummary(long capabilityRevisionId, String capabilityCode,
                                             CapabilityBinding.CapabilityType type, String revision,
                                             String displayName, String assetHash, long reviewedBy,
                                             Instant reviewedAt, int fileCount, long totalBytes) {
    public CapabilityAssetRevisionSummary {
        if (capabilityRevisionId <= 0) throw new IllegalArgumentException("capabilityRevisionId must be positive");
        Objects.requireNonNull(capabilityCode);
        Objects.requireNonNull(type);
        Objects.requireNonNull(revision);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(assetHash);
        Objects.requireNonNull(reviewedAt);
        if (fileCount < 0 || totalBytes < 0) throw new IllegalArgumentException("Asset summary size must not be negative");
    }
}
