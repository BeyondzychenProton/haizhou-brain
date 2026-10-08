package com.haizhuo.brain.platform.employee;

/** 管理员提交的资产文本文件；media type、大小与哈希由服务端计算。 */
public record CapabilityAssetFileInput(String relativePath, String content) {}
