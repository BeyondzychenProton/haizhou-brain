package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.runtime.agentscope.middleware.CapabilityViewMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.ObservabilityMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.RunControlMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.SecurityMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.SessionBridgeMiddleware;
import com.haizhuo.brain.runtime.agentscope.tool.ExternalToolSchemaAssembler;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeToolSchema;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 只依据冻结的 RuntimeDefinitionSnapshot 构建 HarnessAgent（规格 §21）：
 * 模型、定义工作区、静态 toolkit、中间件、状态存储与文件系统全部来自快照
 * 或进程内配置。此处禁止访问：Run 库、User 库、最新草稿、
 * 当前权限状态与凭据（I-08）。
 *
 * <p>Builder 方法名已对照 AgentScope 2.0.3 核验（agentscope-harness-2.0.3.jar，
 * 通过 javap 查看 HarnessAgent$Builder）。注意规格 §21.1 的概念代码写的是
 * {@code disableSubagentTool()}，而 2.0.3 实际暴露的是 {@code disableSubagents()}。</p>
 */
public class HarnessAgentFactory {

    private final AgentScopeModelFactory modelFactory;
    private final AgentStateStore agentStateStore;
    private final RemoteFilesystemSpec filesystemSpec;
    private final DefinitionWorkspaceMaterializer workspaceMaterializer;
    private final RunControlInbox controlInbox;

    /**
     * @param filesystemSpec 可为 null；缺失时 Harness 不带远程工作区文件面运行
     *                       （在配置出 durable BaseStore 之前的 P0 回退方案，见文档）。
     */
    public HarnessAgentFactory(AgentScopeModelFactory modelFactory, AgentStateStore agentStateStore,
                               RemoteFilesystemSpec filesystemSpec,
                               DefinitionWorkspaceMaterializer workspaceMaterializer,
                               RunControlInbox controlInbox) {
        this.modelFactory = Objects.requireNonNull(modelFactory);
        this.agentStateStore = Objects.requireNonNull(agentStateStore);
        this.filesystemSpec = filesystemSpec;
        this.workspaceMaterializer = Objects.requireNonNull(workspaceMaterializer);
        this.controlInbox = Objects.requireNonNull(controlInbox);
    }

    public HarnessRuntimeTemplate build(RuntimeDefinitionSnapshot definition, HarnessTemplateKey key) {
        Toolkit toolkit = ExternalToolSchemaAssembler.assemble(definition.toolCatalog());
        Path definitionWorkspace = workspaceMaterializer.materialize(definition);
        Set<String> externalCatalogNames = definition.toolCatalog().stream()
                .map(RuntimeToolSchema::toolName)
                .collect(Collectors.toUnmodifiableSet());

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(definition.employeeName())
                .description("Haizhuo digital employee " + definition.employeeName())
                .sysPrompt(definition.instructions())
                .model(createModel(definition))
                .toolkit(toolkit)
                .workspace(definitionWorkspace)
                .stateStore(agentStateStore)
                .maxIters(definition.maxIterations())
                .middleware(new SecurityMiddleware())
                .middleware(new CapabilityViewMiddleware(externalCatalogNames))
                .middleware(new SessionBridgeMiddleware())
                .middleware(new RunControlMiddleware(controlInbox))
                .middleware(new ObservabilityMiddleware())
                // I-15：P0/P1 不做自动长期记忆。
                .disableMemoryHooks()
                .disableMemoryTools()
                // P0 安全基线：不用本地 shell、不启动动态子 agent、
                // 不在 worker 磁盘上落 transcript 文件。后续可按策略版本放宽。
                .disableShellTool()
                .disableSubagents()
                .disableTranscript()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableDynamicSubagents()
                .disableToolsConfig()
                .disableAtPathExpansion();
        if (filesystemSpec != null) {
            builder.filesystem(filesystemSpec);
        }
        return new HarnessRuntimeTemplate(key, builder.build(), Instant.now());
    }

    /** 供测试使用：单元测试可替换成不触网络的确定性模型。 */
    protected ChatModelBase createModel(RuntimeDefinitionSnapshot definition) {
        return modelFactory.create(definition);
    }
}
