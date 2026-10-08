package com.haizhuo.brain.platform.employee;

import java.time.Instant;

/** 管理列表使用的脱敏资产摘要，不包含文件正文。 */
public record CapabilityAssetSummary(String capabilityCode, CapabilityBinding.CapabilityType type,
                                     String displayName, int draftRevision, String publishedRevision,
                                     long publishedRevisionId, String assetHash, Instant updatedAt) {}
