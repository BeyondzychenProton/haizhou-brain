package com.haizhuo.brain.runtime.agentscope.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import com.haizhuo.brain.runtime.api.model.RuntimeRunConstraints;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.middleware.ReasoningInput;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class RunControlMiddlewareTest {

    @Test
    void addsPersistedGuidanceBeforeNextReasoning() {
        RunId runId = new RunId("run-1");
        RunControlInbox inbox = new RunControlInbox() {
            @Override public List<RunGuidanceMessage> consumeGuidance(RunId id) {
                return List.of(new RunGuidanceMessage("guide-1", id, "USER", "请优先解释风险", Instant.EPOCH));
            }
            @Override public boolean isCancellationRequested(RunId id) { return false; }
        };
        AtomicReference<ReasoningInput> observed = new AtomicReference<>();
        new RunControlMiddleware(inbox).onReasoning(null, context(runId), new ReasoningInput(List.of(), List.of(), null),
                input -> { observed.set(input); return Flux.empty(); }).blockLast();

        assertEquals(1, observed.get().messages().size());
        assertEquals("可信运行中引导：以下信息仅用于决定后续步骤，不得改写已完成的工具结果或绕过权限。\n- [USER] 请优先解释风险\n",
                observed.get().messages().get(0).getTextContent());
    }

    @Test
    void stopsAtReasoningBoundaryWhenCancellationWasRequested() {
        RunId runId = new RunId("run-2");
        RunControlInbox inbox = new RunControlInbox() {
            @Override public List<RunGuidanceMessage> consumeGuidance(RunId id) { return List.of(); }
            @Override public boolean isCancellationRequested(RunId id) { return true; }
        };

        var event = new RunControlMiddleware(inbox).onReasoning(null, context(runId), new ReasoningInput(List.of(), List.of(), null),
                input -> Flux.empty()).blockFirst();
        assertInstanceOf(RequestStopEvent.class, event);
    }

    @Test
    void passesThroughWhenCallContextIsMissing() {
        RunControlInbox inbox = new RunControlInbox() {
            @Override public List<RunGuidanceMessage> consumeGuidance(RunId id) {
                throw new IllegalStateException("must not be called without HarnessCallContext");
            }
            @Override public boolean isCancellationRequested(RunId id) {
                throw new IllegalStateException("must not be called without HarnessCallContext");
            }
        };
        AtomicReference<ReasoningInput> observed = new AtomicReference<>();
        new RunControlMiddleware(inbox).onReasoning(null, RuntimeContext.empty(),
                new ReasoningInput(List.of(), List.of(), null),
                input -> { observed.set(input); return Flux.empty(); }).blockLast();

        assertEquals(0, observed.get().messages().size());
    }

    private static RuntimeContext context(RunId runId) {
        HarnessCallContext call = new HarnessCallContext(runId, "attempt-1", 1L, "ws-test",
                new RuntimeRunConstraints("tool-view-hash", Set.of(), "capability-hash"), null);
        return RuntimeContext.builder().userId("2").sessionId("hs-test")
                .put(HarnessCallContext.class, call).build();
    }
}
