package com.haizhuo.brain.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.middleware.ReasoningInput;
import java.time.Instant;
import java.util.List;
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

    private static RuntimeContext context(RunId runId) {
        AgentExecutionRequest request = new AgentExecutionRequest(new TenantId(1), new UserId(2), new SessionId("session-1"), runId,
                new TraceId("trace-1"), 1, "员工", "", "openai", "model", List.of(), "问题");
        return RuntimeContext.builder().put(AgentExecutionRequest.class, request).build();
    }
}
