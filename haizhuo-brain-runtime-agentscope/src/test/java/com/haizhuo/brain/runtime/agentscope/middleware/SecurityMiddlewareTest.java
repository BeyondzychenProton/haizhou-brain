package com.haizhuo.brain.runtime.agentscope.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.agentscope.TestRequests;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.AgentInput;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SecurityMiddlewareTest {

    @Test
    void rejectsCallsWithoutHarnessCallContext() {
        AtomicBoolean nextCalled = new AtomicBoolean();
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                new SecurityMiddleware().onAgent(null, RuntimeContext.empty(), null,
                        input -> { nextCalled.set(true); return Flux.empty(); }).blockLast());

        assertTrue(error.getMessage().contains("HarnessCallContext"));
        assertEquals(false, nextCalled.get());
    }

    @Test
    void passesCallsWithHarnessCallContext() {
        HarnessCallContext call = new HarnessCallContext(new RunId("run-1"), "attempt-1", 1L, "ws-test",
                TestRequests.constraints(Set.of()), null);
        RuntimeContext context = RuntimeContext.builder().put(HarnessCallContext.class, call).build();
        AtomicBoolean nextCalled = new AtomicBoolean();

        new SecurityMiddleware().onAgent(null, context, (AgentInput) null,
                input -> { nextCalled.set(true); return Flux.empty(); }).blockLast();

        assertEquals(true, nextCalled.get());
    }
}
