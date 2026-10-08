package com.haizhuo.brain.runtime.api.model;

import java.util.Objects;

/** One immutable, content-verified file from a published employee bundle. */
public record RuntimeWorkspaceFile(String relativePath, String content, String sha256, int byteSize,
                                   String capabilityCode, String capabilityRevision, String capabilityType) {
    public RuntimeWorkspaceFile {
        Objects.requireNonNull(relativePath);
        Objects.requireNonNull(content);
        Objects.requireNonNull(sha256);
        Objects.requireNonNull(capabilityCode);
        Objects.requireNonNull(capabilityRevision);
        Objects.requireNonNull(capabilityType);
        if (byteSize < 0) throw new IllegalArgumentException("byteSize must not be negative");
    }
}
