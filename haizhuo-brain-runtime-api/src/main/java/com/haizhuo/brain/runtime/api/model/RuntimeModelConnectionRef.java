package com.haizhuo.brain.runtime.api.model;

import java.util.Objects;

/** 指向某个确定模型连接版本的不含秘密信息的不可变引用。 */
public record RuntimeModelConnectionRef(String connectionId, int revision, String contentHash,
                                        ReferenceKind kind) {
    public RuntimeModelConnectionRef(String connectionId, int revision, String contentHash) {
        this(connectionId, revision, contentHash, ReferenceKind.MANAGED_REVISION);
    }

    public RuntimeModelConnectionRef {
        Objects.requireNonNull(connectionId);
        Objects.requireNonNull(contentHash);
        Objects.requireNonNull(kind);
        if (connectionId.isBlank() || connectionId.length() > 64)
            throw new IllegalArgumentException("connectionId must contain 1-64 characters");
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        if (!contentHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 value");
    }

/** 显式标记的兼容性固定引用，解析器必须校验其身份和内容摘要。 */
    public static RuntimeModelConnectionRef legacyDeploymentPin(String identity, int revision, String contentHash) {
        return new RuntimeModelConnectionRef(identity, revision, contentHash, ReferenceKind.LEGACY_DEPLOYMENT_PIN);
    }

    public enum ReferenceKind {
        MANAGED_REVISION,
        LEGACY_DEPLOYMENT_PIN
    }
}
