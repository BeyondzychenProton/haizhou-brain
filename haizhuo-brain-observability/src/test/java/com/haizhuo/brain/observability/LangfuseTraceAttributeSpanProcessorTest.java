package com.haizhuo.brain.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;

class LangfuseTraceAttributeSpanProcessorTest {

    @Test
    void copiesLangfuseBaggageToEveryStartedSpan() {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(new LangfuseTraceAttributeSpanProcessor())
                .build();
        try {
            Tracer tracer = provider.get("test");
            Context parent = Baggage.builder()
                    .put("langfuse.user.id", "42")
                    .put("langfuse.trace.metadata.run_id", "run-1")
                    .build()
                    .storeInContext(Context.root());
            Span span = tracer.spanBuilder("child").setParent(parent).startSpan();
            try {
                ReadableSpan readable = (ReadableSpan) span;
                assertEquals("42", readable.getAttribute(
                        AttributeKey.stringKey("langfuse.user.id")));
                assertEquals("run-1", readable.getAttribute(
                        AttributeKey.stringKey("langfuse.trace.metadata.run_id")));
            } finally {
                span.end();
            }
        } finally {
            provider.close();
        }
    }
}
