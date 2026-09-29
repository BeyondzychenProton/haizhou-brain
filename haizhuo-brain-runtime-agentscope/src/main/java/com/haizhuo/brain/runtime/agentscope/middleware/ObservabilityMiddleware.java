package com.haizhuo.brain.runtime.agentscope.middleware;

import com.haizhuo.brain.observability.LangfuseCallContext;
import com.haizhuo.brain.observability.LangfuseProperties;
import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 在 agent/推理边界上的最小结构化日志（规格 §5.4）。只记录标识与计数——
 * 绝不记录消息正文、工具参数、供应商响应或密钥。
 */
public class ObservabilityMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityMiddleware.class);

    private final Tracer tracer;
    private final LangfuseProperties properties;
    private final Clock clock;

    public ObservabilityMiddleware() {
        // 测试/兼容构造器不能提前触发 GlobalOpenTelemetry 的 no-op 注册；
        // Spring 装配路径会显式注入 Boot 的 Tracer。
        this(OpenTelemetry.noop().getTracer("com.haizhuo.brain"),
                new LangfuseProperties(false, null, null, null, null, null, null,
                        false, 2000, 256, 250, "4"), Clock.systemUTC());
    }

    public ObservabilityMiddleware(Tracer tracer, LangfuseProperties properties) {
        this(tracer, properties, Clock.systemUTC());
    }

    ObservabilityMiddleware(Tracer tracer, LangfuseProperties properties, Clock clock) {
        this.tracer = tracer;
        this.properties = properties;
        this.clock = clock;
    }

    /** 让本中间件位于 AgentScope 原生 OtelTracingMiddleware 的外层。 */
    @Override
    public int order() {
        return 100;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        HarnessCallContext call = context.get(HarnessCallContext.class);
        if (call != null) {
            log.info("harness.call.started runId={} attemptId={} fenceToken={}",
                    call.runId().value(), call.attemptId(), call.fenceToken());
        }
        LangfuseCallContext langfuse = context.get(LangfuseCallContext.class);
        if (langfuse == null || !properties.isEnabled()) return next.apply(input);

        Instant startedAt = clock.instant();
        SpanContext syntheticParent = SpanContext.create(langfuse.traceId(),
                langfuse.parentSpanId(), TraceFlags.getSampled(), TraceState.getDefault());
        Context parent = Context.root().with(Span.wrap(syntheticParent));
        Baggage baggage = Baggage.builder()
                .put("langfuse.user.id", langfuse.userId())
                .put("langfuse.session.id", langfuse.sessionId())
                .put("langfuse.trace.metadata.run_id", langfuse.runId())
                .put("langfuse.trace.metadata.attempt_id", langfuse.attemptId())
                .put("langfuse.trace.name", "haizhuo.run")
                .put("langfuse.environment", properties.environment())
                .build();
        parent = baggage.storeInContext(parent);
        Span span = tracer.spanBuilder("haizhuo.run")
                .setParent(parent)
                .setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(startedAt)
                .setAttribute("gen_ai.operation.name", "invoke_agent")
                .setAttribute("gen_ai.agent.name", agent.getName())
                .setAttribute("gen_ai.request.messages.count", (long) input.msgs().size())
                .setAttribute("langfuse.observation.type", "agent")
                .setAttribute("langfuse.trace.name", "haizhuo.run")
                .setAttribute("langfuse.user.id", langfuse.userId())
                .setAttribute("langfuse.session.id", langfuse.sessionId())
                .setAttribute("langfuse.trace.metadata.run_id", langfuse.runId())
                .setAttribute("langfuse.trace.metadata.attempt_id", langfuse.attemptId())
                .setAttribute("langfuse.trace.metadata.environment", properties.environment())
                .setAttribute("langfuse.environment", properties.environment())
                .startSpan();
        langfuse.agentObservationId(span.getSpanContext().getSpanId());
        Context spanContext = span.storeInContext(parent);
        AtomicBoolean ended = new AtomicBoolean();
        Flux<AgentEvent> downstream = next.apply(input)
                .doOnComplete(() -> end(span, ended, StatusCode.OK, null))
                .doOnError(error -> end(span, ended, StatusCode.ERROR, error))
                .doOnCancel(() -> end(span, ended, StatusCode.ERROR, null));
        return ContextPropagationOperator.runWithContext(downstream, spanContext);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        HarnessCallContext call = context.get(HarnessCallContext.class);
        if (call != null) {
            log.debug("harness.reasoning.started runId={} toolCount={}",
                    call.runId().value(), input.tools().size());
        }
        return next.apply(input);
    }

    private void end(Span span, AtomicBoolean ended, StatusCode status, Throwable error) {
        if (!ended.compareAndSet(false, true)) return;
        if (status == StatusCode.ERROR) {
            String message = error == null ? "cancelled" : error.getClass().getSimpleName();
            span.setStatus(StatusCode.ERROR, message);
            if (error != null) span.recordException(error);
        } else {
            span.setStatus(StatusCode.OK);
        }
        span.end(clock.instant());
    }
}
