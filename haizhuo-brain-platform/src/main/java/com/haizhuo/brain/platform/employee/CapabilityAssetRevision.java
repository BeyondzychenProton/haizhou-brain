package com.haizhuo.brain.platform.employee;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 已审核、不可变且按 capabilityRevisionId 寻址的资产修订。 */
public record CapabilityAssetRevision(long capabilityRevisionId, String capabilityCode,
                                      CapabilityBinding.CapabilityType type, String revision,
                                      String displayName, String description, List<CapabilityAssetFile> files,
                                      String manifestJson, String assetHash, String publishRequestId,
                                      long reviewedBy, Instant reviewedAt) {
    public CapabilityAssetRevision {
        if (capabilityRevisionId <= 0) throw new IllegalArgumentException("capabilityRevisionId must be positive");
        Objects.requireNonNull(capabilityCode);
        Objects.requireNonNull(type);
        Objects.requireNonNull(revision);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(description);
        files = List.copyOf(Objects.requireNonNull(files));
        Objects.requireNonNull(manifestJson);
        Objects.requireNonNull(assetHash);
        Objects.requireNonNull(publishRequestId);
        Objects.requireNonNull(reviewedAt);
    }
}
