package com.haizhuo.brain.observability;

import com.haizhuo.brain.kernel.identity.RunId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Langfuse trace ID 以 Run 为稳定边界，跨挂起、工具 worker 和恢复尝试保持不变。 */
public final class LangfuseTraceIds {
    private LangfuseTraceIds() { }

    public static String forRun(RunId runId) {
        return hex(UUID.nameUUIDFromBytes(("haizhuo-brain:run:" + runId.value())
                .getBytes(StandardCharsets.UTF_8)));
    }

    /** OTel SpanId 必须是 16 个十六进制字符；同一尝试使用稳定但非持久的父 ID。 */
    public static String spanIdFor(String traceId, String seed) {
        UUID value = UUID.nameUUIDFromBytes((traceId + ":" + seed)
                .getBytes(StandardCharsets.UTF_8));
        return hex(value).substring(0, 16);
    }

    private static String hex(UUID value) {
        return "%016x%016x".formatted(value.getMostSignificantBits(), value.getLeastSignificantBits());
    }
}
