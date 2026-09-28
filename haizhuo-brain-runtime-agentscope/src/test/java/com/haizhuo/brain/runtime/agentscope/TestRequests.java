package com.haizhuo.brain.runtime.agentscope;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.runtime.api.model.AgentExecutionInput;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeRunConstraints;
import com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding;
import com.haizhuo.brain.runtime.api.model.RuntimeToolSchema;
import com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** runtime-agentscope 单元测试的共享夹具。 */
public final class TestRequests {

    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();

    private TestRequests() {
    }

    public static RuntimeToolSchema tool(long revisionId, String toolName) {
        return new RuntimeToolSchema(revisionId, "ref-" + toolName, toolName, "测试工具 " + toolName,
                Map.of("type", "object", "properties", Map.of()), false, false, "test-action");
    }

    public static RuntimeDefinitionSnapshot definition(String bundleHash, List<RuntimeToolSchema> tools) {
        return new RuntimeDefinitionSnapshot(1L, "员工小卓", "你是海卓数字员工。", "openai", "test-model",
                3, bundleHash, "ws-projection", "workspace-hash-" + bundleHash, tools);
    }

    public static RuntimeRunConstraints constraints(Set<String> visibleTools) {
        return new RuntimeRunConstraints("tool-view-hash", visibleTools, "capability-hash");
    }

    public static RuntimeSessionBinding binding(String harnessSessionKey, String workspaceRuntimeKey,
                                                String bridgeContext) {
        return new RuntimeSessionBinding(harnessSessionKey, workspaceRuntimeKey, "bridge-hash", bridgeContext);
    }

    public static AgentExecutionRequest request(RuntimeDefinitionSnapshot definition,
                                                RuntimeRunConstraints constraints,
                                                RuntimeSessionBinding binding,
                                                AgentExecutionInput input) {
        return new AgentExecutionRequest(new TenantId(1), new UserId(2), new SessionId("session-1"),
                new RunId("run-" + RUN_SEQUENCE.incrementAndGet()), new TraceId("trace-1"), "attempt-1", 1L,
                definition, constraints, binding, input);
    }

    public static AgentExecutionRequest promptRequest(RuntimeDefinitionSnapshot definition, String prompt) {
        return request(definition, constraints(Set.of()), binding("hs-test", "ws-test", null),
                new UserPromptExecutionInput(prompt));
    }
}
