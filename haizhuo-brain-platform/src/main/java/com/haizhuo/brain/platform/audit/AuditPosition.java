package com.haizhuo.brain.platform.audit;

import java.time.Instant;

/** 全局 (createdAt DESC, auditId DESC) 排序中的排他式 keyset 位置。 */
public record AuditPosition(Instant createdAt, String auditId) {
}
