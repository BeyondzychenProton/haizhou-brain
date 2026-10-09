package com.haizhuo.brain.platform.employee;

import java.util.List;
import java.util.Optional;

/** 技能/知识资产草稿、不可变修订与审计的一致性边界。 */
public interface CapabilityAssetRepository {
    List<CapabilityAssetSummary> listAssetSummaries();
    boolean assetExists(String capabilityCode);
    CapabilityAssetRevisionPage listRevisionSummaries(String capabilityCode, String cursor, int limit);
    Optional<CapabilityAssetDraft> findDraft(String capabilityCode);
    Optional<CapabilityAssetRevision> findRevision(String capabilityCode, long capabilityRevisionId);
    default Optional<CapabilityAssetRevision> findRevisionByCodeAndRevision(String capabilityCode, String revision) {
        return Optional.empty();
    }
    CapabilityAssetDraft saveDraft(CapabilityAssetDraft draft, int expectedDraftRevision, String reason);
    CapabilityAssetRevision publish(String capabilityCode, int expectedDraftRevision, String requestId,
                                    long actorId, String reason);
}
