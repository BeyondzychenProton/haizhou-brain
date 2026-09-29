package com.haizhuo.brain.observability;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.RunObservationSink;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 把平台生命周期和实际工具落定补充为 OTLP observations，并把可评测结果写入
 * Langfuse Score API。模型调用、Agent 调用和 AgentScope 工具意图由原生
 * {@code OtelTracingMiddleware} 负责，这个类只处理平台边界事件。
 */
@Component
public class LangfuseObservationRecorder implements RunObservationSink {
    private final Tracer tracer;
    private final LangfuseProperties properties;
    private final LangfuseScoreClient scores;
    private final Clock clock;

    public LangfuseObservationRecorder(Tracer tracer, LangfuseProperties properties,
                                       LangfuseScoreClient scores, Clock clock) {
        this.tracer = Objects.requireNonNull(tracer);
        this.properties = Objects.requireNonNull(properties);
        this.scores = Objects.requireNonNull(scores);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 仅用于测试或非 Spring 装配路径。 */
    public LangfuseObservationRecorder() {
        this(OpenTelemetry.noop().getTracer("com.haizhuo.brain"),
                new LangfuseProperties(false, null, null, null, null, null, null,
                        false, 2000, 16, 250, "4"),
                new LangfuseScoreClient(new LangfuseProperties(false, null, null, null, null,
                        null, null, false, 2000, 16, 250, "4"),
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                Clock.systemUTC());
    }

    public void runStarted(AgentExecutionRequest request) {
        // 根 span 在 ObservabilityMiddleware 创建；这里保留生命周期端口以便后续补充。
    }

    public void runCompleted(AgentExecutionRequest request, AgentRunCompletedEvent event) {
        recordRunEvent(request, "run.completed", "OK", event.result());
        score(request.runId().value(), null, "run.completed", 1.0, "NUMERIC", "Run completed");
    }

    public void runFailed(AgentExecutionRequest request, AgentRunFailedEvent event) {
        String message = truncate(event.message());
        recordRunEvent(request, "run.failed", "ERROR", message);
        score(request.runId().value(), null, "run.completed", 0.0, "NUMERIC", message);
    }

    public void runSuspended(AgentExecutionRequest request, int toolCount) {
        recordRunEvent(request, "run.waiting_tool", "WARNING", "toolCount=" + toolCount);
    }

    @Override
    public void onToolExecution(String runId, String sessionId, String userId,
                                String executionId, String toolName,
                                long capabilityRevisionId, String state, String errorCode,
                                String inputDigest, Instant startedAt, Instant finishedAt) {
        if (!properties.isEnabled()) return;
        String traceId = LangfuseTraceIds.forRun(new RunId(runId));
        String spanId = LangfuseTraceIds.spanIdFor(traceId, "tool:" + executionId);
        Span span = startDetached(traceId, spanId, null, "tool " + toolName, startedAt,
                "tool", "tool." + toolName, state);
        String actualObservationId = span.getSpanContext().getSpanId();
        try {
            span.setAttribute("gen_ai.operation.name", "execute_tool");
            span.setAttribute("gen_ai.tool.name", toolName);
            // 工具 worker 在独立线程落定结果，没有 AgentScope baggage；显式补齐
            // Langfuse 要求出现在每个 span 上的 trace-level 属性。
            span.setAttribute("langfuse.user.id", userId);
            span.setAttribute("langfuse.session.id", sessionId);
            span.setAttribute("langfuse.trace.metadata.run_id", runId);
            span.setAttribute("langfuse.trace.metadata.environment", properties.environment());
            span.setAttribute("langfuse.observation.metadata.execution_id", executionId);
            span.setAttribute("langfuse.observation.metadata.capability_revision_id",
                    Long.toString(capabilityRevisionId));
            span.setAttribute("langfuse.observation.metadata.input_digest", inputDigest);
            if (errorCode != null && !errorCode.isBlank()) {
                span.setAttribute("langfuse.observation.status_message", truncate(errorCode));
            }
            if (isFailure(state)) span.setStatus(StatusCode.ERROR, truncate(errorCode));
            else span.setStatus(StatusCode.OK);
        } finally {
            span.end(finishedAt);
        }
        if (isTerminal(state)) {
            score(traceId, actualObservationId, "tool.success", "SUCCEEDED".equals(state) ? 1.0 : 0.0,
                    "NUMERIC", toolName + " -> " + state);
        }
    }

    public boolean score(String traceId, String observationId, String name, double value,
                         String dataType, String comment) {
        if (traceId == null || traceId.isBlank() || name == null || name.isBlank()) return false;
        return scores.submit(traceId, observationId, name, value, dataType, truncate(comment));
    }

    private void recordRunEvent(AgentExecutionRequest request, String name, String level,
                                String output) {
        if (!properties.isEnabled()) return;
        LangfuseCallContext context = context(request);
        Instant now = clock.instant();
        String spanId = context.nextObservationId(name);
        Span span = startDetached(context.traceId(), spanId, context.agentObservationId(), name,
                now, "event", level, level);
        try {
            setTraceAttributes(span, context, request);
            span.setAttribute("langfuse.observation.metadata.event", name);
            if (output != null) {
                span.setAttribute("langfuse.observation.metadata.output_length", output.length());
                if (properties.captureContentEnabled()) {
                    span.setAttribute("langfuse.observation.output", truncate(output));
                }
            }
            if ("ERROR".equals(level)) span.setStatus(StatusCode.ERROR, truncate(output));
            else if ("WARNING".equals(level)) span.setStatus(StatusCode.UNSET);
            else span.setStatus(StatusCode.OK);
        } finally {
            span.end(now);
        }
    }

    private Span startDetached(String traceId, String spanId, String parentSpanId, String name,
                               Instant startedAt, String type, String operation, String level) {
        String effectiveParentSpanId = parentSpanId == null || parentSpanId.isBlank()
                ? LangfuseTraceIds.spanIdFor(traceId, "parent:" + spanId)
                : parentSpanId;
        SpanContext syntheticParent = SpanContext.create(traceId,
                effectiveParentSpanId,
                TraceFlags.getSampled(), TraceState.getDefault());
        Span span = tracer.spanBuilder(name)
                .setParent(Context.root().with(Span.wrap(syntheticParent)))
                .setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(startedAt)
                .startSpan();
        span.setAttribute("langfuse.observation.type", type);
        span.setAttribute("langfuse.observation.level", normalizeLevel(level));
        span.setAttribute("langfuse.trace.name", "haizhuo.run");
        span.setAttribute("langfuse.observation.metadata.operation", operation);
        span.setAttribute("langfuse.observation.metadata.span_id",
                span.getSpanContext().getSpanId());
        return span;
    }

    private static String normalizeLevel(String level) {
        return switch (level) {
            case "DEBUG", "WARNING", "ERROR" -> level;
            case "FAILED", "DENIED", "RECONCILIATION_REQUIRED", "CANCELLED" -> "ERROR";
            case "APPROVAL_REQUIRED" -> "WARNING";
            default -> "DEFAULT";
        };
    }

    private void setTraceAttributes(Span span, LangfuseCallContext context,
                                    AgentExecutionRequest request) {
        span.setAttribute("langfuse.user.id", context.userId());
        span.setAttribute("langfuse.session.id", context.sessionId());
        span.setAttribute("langfuse.trace.metadata.run_id", context.runId());
        span.setAttribute("langfuse.trace.metadata.attempt_id", context.attemptId());
        span.setAttribute("langfuse.trace.metadata.environment", properties.environment());
        span.setAttribute("langfuse.trace.metadata.definition_version_id",
                request.definition().definitionVersionId());
    }

    private static LangfuseCallContext context(AgentExecutionRequest request) {
        return new LangfuseCallContext(request.runId(), request.platformSessionId().value(),
                Long.toString(request.userId().value()), request.attemptId());
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() <= properties.maxContentChars()
                ? value : value.substring(0, properties.maxContentChars()) + "…";
    }

    private static boolean isTerminal(String state) {
        return "SUCCEEDED".equals(state) || "FAILED".equals(state) || "DENIED".equals(state)
                || "RECONCILIATION_REQUIRED".equals(state) || "CANCELLED".equals(state);
    }

    private static boolean isFailure(String state) {
        return "FAILED".equals(state) || "DENIED".equals(state)
                || "RECONCILIATION_REQUIRED".equals(state) || "CANCELLED".equals(state);
    }
}
