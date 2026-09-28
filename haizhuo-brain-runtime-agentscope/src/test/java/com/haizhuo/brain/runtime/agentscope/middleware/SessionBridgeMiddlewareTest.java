package com.haizhuo.brain.runtime.agentscope.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.agentscope.TestRequests;
import io.agentscope.core.agent.RuntimeContext;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionBridgeMiddlewareTest {

    @Test
    void appendsBridgeContextToTheSystemPrompt() {
        RuntimeContext context = contextWithBridge("上一轮讨论了会议室预订。");

        String prompt = new SessionBridgeMiddleware().onSystemPrompt(null, context, "BASE").block();

        assertTrue(prompt.startsWith("BASE"));
        assertTrue(prompt.contains("上一轮讨论了会议室预订。"));
        assertTrue(prompt.contains("会话桥接摘要"));
    }

    @Test
    void keepsPromptUntouchedWithoutBridgeContext() {
        assertEquals("BASE", new SessionBridgeMiddleware()
                .onSystemPrompt(null, contextWithBridge(null), "BASE").block());
        assertEquals("BASE", new SessionBridgeMiddleware()
                .onSystemPrompt(null, contextWithBridge("  "), "BASE").block());
        assertEquals("BASE", new SessionBridgeMiddleware()
                .onSystemPrompt(null, RuntimeContext.empty(), "BASE").block());
    }

    private static RuntimeContext contextWithBridge(String bridgeContext) {
        HarnessCallContext call = new HarnessCallContext(new RunId("run-1"), "attempt-1", 1L, "ws-test",
                TestRequests.constraints(Set.of()), bridgeContext);
        return RuntimeContext.builder().userId("2").sessionId("hs-test")
                .put(HarnessCallContext.class, call).build();
    }
}
