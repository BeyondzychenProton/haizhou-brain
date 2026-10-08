package com.haizhuo.brain.platform.employee;

/** 可供管理 API 定位到具体文件字段的输入校验错误。 */
public final class CapabilityAssetValidationException extends IllegalArgumentException {
    private final String fieldPath;

    public CapabilityAssetValidationException(String fieldPath, String message) {
        super(message);
        this.fieldPath = fieldPath;
    }

    public String fieldPath() { return fieldPath; }
}
