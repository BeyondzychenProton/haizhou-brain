package com.haizhuo.brain.platform.employee;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 可编辑资产草稿。它不能被 Run 直接引用，只有发布事务生成的 revision 才可冻结到定义包。 */
public record CapabilityAssetDraft(String capabilityCode, CapabilityBinding.CapabilityType type,
                                   int draftRevision, String displayName, String description,
                                   List<CapabilityAssetFile> files, String manifestJson, String assetHash,
                                   long updatedBy, Instant updatedAt) {
    public CapabilityAssetDraft {
        Objects.requireNonNull(capabilityCode);
        Objects.requireNonNull(type);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(description);
        files = List.copyOf(Objects.requireNonNull(files));
        Objects.requireNonNull(manifestJson);
        Objects.requireNonNull(assetHash);
        Objects.requireNonNull(updatedAt);
        if (draftRevision < 1) throw new IllegalArgumentException("draftRevision must be positive");
    }
}
