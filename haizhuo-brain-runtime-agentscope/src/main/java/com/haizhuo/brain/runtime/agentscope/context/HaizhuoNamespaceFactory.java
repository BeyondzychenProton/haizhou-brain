package com.haizhuo.brain.runtime.agentscope.context;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import java.util.List;

/**
 * 工作区运行时命名空间工厂（规格 §25.1）。工作区数据面由调用级 HarnessCallContext 中的
 * workspaceRuntimeKey 寻址——它与 AgentState 不是同一个命名空间，
 * 后者按 (userId, harnessSessionKey) 寻址（I-03）。
 */
public final class HaizhuoNamespaceFactory implements NamespaceFactory {

    @Override
    public List<String> getNamespace(RuntimeContext context) {
        HarnessCallContext call = context.get(HarnessCallContext.class);
        if (call == null || call.workspaceRuntimeKey() == null || call.workspaceRuntimeKey().isBlank()) {
            throw new IllegalStateException("workspaceRuntimeKey is required in HarnessCallContext");
        }
        return List.of("haizhuo", "runtime", call.workspaceRuntimeKey());
    }
}
