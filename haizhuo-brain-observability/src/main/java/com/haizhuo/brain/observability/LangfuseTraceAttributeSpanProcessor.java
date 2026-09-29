package com.haizhuo.brain.observability;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 将 Run 根 span 设置的 Langfuse trace 属性复制到 AgentScope 创建的子 span。
 * Langfuse 的过滤与聚合要求 user/session/metadata 等 trace 属性出现在每个 span 上。
 */
@Component
public final class LangfuseTraceAttributeSpanProcessor implements SpanProcessor {
    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        Baggage baggage = Baggage.fromContextOrNull(parentContext);
        if (baggage == null || baggage.isEmpty()) return;
        for (Map.Entry<String, io.opentelemetry.api.baggage.BaggageEntry> entry
                : baggage.asMap().entrySet()) {
            if (entry.getKey().startsWith("langfuse.")) {
                span.setAttribute(entry.getKey(), entry.getValue().getValue());
            }
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // BatchSpanProcessor 负责异步导出；这里不做网络或持久化操作。
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }
}
