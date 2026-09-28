package com.haizhuo.brain.runtime.agentscope.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.haizhuo.brain.runtime.agentscope.TestRequests;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import io.agentscope.core.agent.RuntimeContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeContextFactoryTest {

    @Test
    void mapsRequestIntoRuntimeContextWithCallScope() {
        AgentExecutionRequest request = TestRequests.request(
                TestRequests.definition("bundle-1", List.of()),
                TestRequests.constraints(Set.of("meeting.reserve")),
                TestRequests.binding("hs-abc", "ws-abc", "桥接摘要"),
                new com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput("你好"));

        RuntimeContext context = new RuntimeContextFactory().create(request);

        assertEquals("2", context.getUserId());
        assertEquals("hs-abc", context.getSessionId());
        HarnessCallContext call = context.get(HarnessCallContext.class);
        assertEquals(request.runId(), call.runId());
        assertEquals("attempt-1", call.attemptId());
        assertEquals(1L, call.fenceToken());
        assertEquals("ws-abc", call.workspaceRuntimeKey());
        assertEquals(Set.of("meeting.reserve"), call.constraints().modelVisibleToolNames());
        assertEquals("桥接摘要", call.bridgeContext());
    }

    @Test
    void toleratesMissingBridgeContext() {
        AgentExecutionRequest request = TestRequests.promptRequest(
                TestRequests.definition("bundle-1", List.of()), "你好");

        HarnessCallContext call = new RuntimeContextFactory().create(request).get(HarnessCallContext.class);

        assertNull(call.bridgeContext());
    }
}
