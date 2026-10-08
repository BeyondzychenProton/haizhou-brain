package com.haizhuo.brain.platform.employee;

/** 管理员提交的 expectedDraftRevision 已过期；客户端应重新读取草稿后再保存。 */
public final class CapabilityAssetDraftConflictException extends RuntimeException {
    public CapabilityAssetDraftConflictException(String message) { super(message); }
}
