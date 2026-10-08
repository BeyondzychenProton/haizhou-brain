package com.haizhuo.brain.runtime.agentscope.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.haizhuo.brain.runtime.agentscope.TestRequests;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding;
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
        assertEquals(RuntimeSessionBinding.COORDINATOR_ROLE, call.roleId(),
                "旧桥接 Session 的空角色只代表根协调者，不应拒绝已有会话");
    }

    @Test
    void directSessionUsesBusinessIdWithoutBridge() {
        var request = TestRequests.request(TestRequests.definition("bundle-direct", List.of()),
                TestRequests.constraints(Set.of()),
                com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding.direct("session-1", false),
                new com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput("你好"));
        RuntimeContext context = new RuntimeContextFactory().create(request);
        assertEquals(request.platformSessionId().value(), context.getSessionId());
        assertNull(context.get(HarnessCallContext.class).bridgeContext());
        assertEquals(RuntimeSessionBinding.COORDINATOR_ROLE,
                context.get(HarnessCallContext.class).roleId());
    }

    @Test
    void roleSlotUsesItsServerOwnedSessionAndWorkspaceKeys() {
        var binding = com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding.roleSlot(
                "researcher", "native-role-session", "workspace-role", false);
        var request = TestRequests.request(TestRequests.definition("bundle-role", List.of()),
                TestRequests.constraints(Set.of()), binding,
                new com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput("研究"));

        RuntimeContext context = new RuntimeContextFactory().create(request);

        assertEquals("native-role-session", context.getSessionId());
        assertEquals("workspace-role", context.get(HarnessCallContext.class).workspaceRuntimeKey());
        assertEquals("researcher", context.get(HarnessCallContext.class).roleId());
    }

    @Test
    void toleratesMissingBridgeContext() {
        AgentExecutionRequest request = TestRequests.promptRequest(
                TestRequests.definition("bundle-1", List.of()), "你好");

        HarnessCallContext call = new RuntimeContextFactory().create(request).get(HarnessCallContext.class);

        assertNull(call.bridgeContext());
    }
}
