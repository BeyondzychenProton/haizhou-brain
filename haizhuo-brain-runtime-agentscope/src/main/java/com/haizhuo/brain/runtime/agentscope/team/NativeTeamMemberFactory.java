package com.haizhuo.brain.runtime.agentscope.team;

import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.agentscope.factory.AgentScopeModelFactory;
import com.haizhuo.brain.runtime.agentscope.factory.DefinitionWorkspaceMaterializer;
import com.haizhuo.brain.runtime.agentscope.middleware.RunControlMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.SecurityMiddleware;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import java.util.Objects;

/** Builds one frozen, read-only HarnessAgent for a native Team member session. */
public final class NativeTeamMemberFactory {
    private final AgentScopeModelFactory modelFactory;
    private final AgentStateStore stateStore;
    private final DefinitionWorkspaceMaterializer workspaceMaterializer;
    private final RunControlInbox controlInbox;

    public NativeTeamMemberFactory(AgentScopeModelFactory modelFactory, AgentStateStore stateStore,
                                   DefinitionWorkspaceMaterializer workspaceMaterializer,
                                   RunControlInbox controlInbox) {
        this.modelFactory = Objects.requireNonNull(modelFactory);
        this.stateStore = Objects.requireNonNull(stateStore);
        this.workspaceMaterializer = Objects.requireNonNull(workspaceMaterializer);
        this.controlInbox = Objects.requireNonNull(controlInbox);
    }

    public HarnessAgent build(RuntimeDefinitionSnapshot definition, String roleId, String sessionId,
                              TeamClient client, TeamContext teamContext, MessageBus bus) {
        if (definition == null || roleId == null || roleId.isBlank() || sessionId == null || sessionId.isBlank())
            throw new IllegalArgumentException("frozen Team member identity is required");
        Objects.requireNonNull(client);
        Objects.requireNonNull(teamContext);
        Objects.requireNonNull(bus);
        HarnessAgent agent = HarnessAgent.builder()
                .name(definition.employeeName())
                .agentId("team-" + sessionId)
                .description("Read-only AgentScope Team member " + roleId)
                .sysPrompt(definition.instructions() + "\n\n你是本次只读 Team 的固定成员。"
                        + "只能使用 native team 工具按分配的工作项协作；不得调用业务工具、访问/修改文件、"
                        + "运行 Shell、创建子 Agent 或暴露内部消息。")
                .model(modelFactory.create(definition))
                .toolkit(new Toolkit())
                .workspace(workspaceMaterializer.materialize(definition))
                .stateStore(stateStore)
                .maxIters(4)
                .middleware(new SecurityMiddleware())
                .middleware(new RunControlMiddleware(controlInbox))
                .teamsMode(client, teamContext, sessionId)
                .messageBus(bus)
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableTranscript()
                .disableToolsConfig()
                .disableAtPathExpansion()
                .build();
        // No background tools are enabled for this profile; wakeups run through the native dispatcher.
        agent.getToolkit().removeTool("wait_async_results");
        // Harness 2.0.3 always constructs these read-only network tools and has no builder disable flag.
        agent.getToolkit().removeTool("web_fetch");
        agent.getToolkit().removeTool("web_search");
        return agent;
    }

    public RuntimeContext runtimeContext(String userId, String sessionId, HarnessCallContext parentCall,
                                         String roleId, String teamWorkspaceKey) {
        HarnessCallContext memberCall = new HarnessCallContext(parentCall.runId(), parentCall.attemptId(),
                parentCall.fenceToken(), teamWorkspaceKey, parentCall.constraints(), null, roleId);
        return RuntimeContext.builder().userId(userId).sessionId(sessionId)
                .put(HarnessCallContext.class, memberCall).build();
    }
}
