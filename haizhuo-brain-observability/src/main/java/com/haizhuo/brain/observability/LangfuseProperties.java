package com.haizhuo.brain.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse OTLP 与评分 API 配置。
 *
 * <p>密钥只从部署配置读取，不进入 RuntimeContext、Run 事件或 OTLP 属性。OTLP
 * endpoint 默认指向 Langfuse v4 的 HTTP/protobuf traces 入口；部署方也可以显式
 * 覆盖为 Collector 地址。</p>
 */
@ConfigurationProperties(prefix = "haizhuo.brain.observability.langfuse")
public record LangfuseProperties(Boolean enabled, String baseUrl, String otlpEndpoint,
                                  String publicKey, String secretKey, String environment,
                                  String serviceName, Boolean captureContent,
                                  Integer maxContentChars, Integer scoreQueueCapacity,
                                  Integer scoreFlushIntervalMillis, String ingestionVersion) {
    public LangfuseProperties {
        if (enabled == null) enabled = false;
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://172.16.1.29:3001";
        if (otlpEndpoint == null || otlpEndpoint.isBlank()) {
            otlpEndpoint = baseUrl.replaceAll("/+$", "") + "/api/public/otel/v1/traces";
        }
        if (environment == null || environment.isBlank()) environment = "local";
        if (serviceName == null || serviceName.isBlank()) serviceName = "haizhuo-brain";
        if (captureContent == null) captureContent = false;
        if (maxContentChars == null || maxContentChars <= 0) maxContentChars = 2000;
        if (scoreQueueCapacity == null || scoreQueueCapacity <= 0) scoreQueueCapacity = 256;
        if (scoreFlushIntervalMillis == null || scoreFlushIntervalMillis <= 0) {
            scoreFlushIntervalMillis = 250;
        }
        if (ingestionVersion == null || ingestionVersion.isBlank()) ingestionVersion = "4";
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean configured() {
        return isEnabled() && notBlank(publicKey) && notBlank(secretKey);
    }

    public boolean captureContentEnabled() {
        return Boolean.TRUE.equals(captureContent);
    }

    public String scoreEndpoint() {
        return baseUrl.replaceAll("/+$", "") + "/api/public/scores";
    }

    public String basicAuthorization() {
        if (!isEnabled() || !notBlank(publicKey) || !notBlank(secretKey)) return null;
        String raw = publicKey + ":" + secretKey;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
