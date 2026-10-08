package com.haizhuo.brain.platform.employee;

import java.util.Objects;

/** 已校验的纯文本资产文件；正文只进入资产草稿/修订仓储，不写管理审计摘要。 */
public record CapabilityAssetFile(String relativePath, String mediaType, String content,
                                  String sha256, int byteSize) {
    public CapabilityAssetFile {
        Objects.requireNonNull(relativePath);
        Objects.requireNonNull(mediaType);
        Objects.requireNonNull(content);
        Objects.requireNonNull(sha256);
        if (byteSize < 0) throw new IllegalArgumentException("byteSize must not be negative");
    }
}
