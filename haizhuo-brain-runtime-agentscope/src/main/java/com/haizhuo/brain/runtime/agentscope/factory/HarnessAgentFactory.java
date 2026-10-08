package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.runtime.agentscope.middleware.CapabilityViewMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.FixedExpertScopeMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.DelegatedChildScopeMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.DelegationAcceptanceMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.ObservabilityMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.RunControlMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.SecurityMiddleware;
import com.haizhuo.brain.runtime.agentscope.middleware.SessionBridgeMiddleware;
import com.haizhuo.brain.runtime.agentscope.team.SynchronousOnlyHarnessTransport;
import com.haizhuo.brain.runtime.agentscope.team.NativeTeamExecutionGateway;
import com.haizhuo.brain.runtime.agentscope.team.RunReadonlyTeamTool;
import com.haizhuo.brain.runtime.agentscope.tool.RunWorkItemTools;
import com.haizhuo.brain.runtime.agentscope.tool.ExternalToolSchemaAssembler;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeToolSchema;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentSpecGenerator;
import io.agentscope.harness.agent.tool.AgentGenerateTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.haizhuo.brain.runtime.agentscope.context.HaizhuoNamespaceFactory;
import com.haizhuo.brain.runtime.agentscope.context.RunScopedDelegationBudget;

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

    /** 计划文件相对定义工作区的目录名，由 Harness PlanModeManager 使用。 */
    private static final String PLAN_DIRECTORY = "plans";

    private final AgentScopeModelFactory modelFactory;
    private final AgentStateStore agentStateStore;
    private final RemoteFilesystemSpec filesystemSpec;
    private final DistributedStore distributedStore;
    private final DefinitionWorkspaceMaterializer workspaceMaterializer;
    private final RunControlInbox controlInbox;
    private final ObservabilityMiddleware observabilityMiddleware;
    private final boolean otelTracingEnabled;
    private final NativeTeamExecutionGateway nativeTeamExecutionGateway;

    /**
     * @param filesystemSpec 可为 null；缺失时 Harness 不带远程工作区文件面运行
     *                       （在配置出 durable BaseStore 之前的 P0 回退方案，见文档）。
     */
    public HarnessAgentFactory(AgentScopeModelFactory modelFactory, AgentStateStore agentStateStore,
                               RemoteFilesystemSpec filesystemSpec,
                               DefinitionWorkspaceMaterializer workspaceMaterializer,
                               RunControlInbox controlInbox) {
        this(modelFactory, agentStateStore, null, filesystemSpec, workspaceMaterializer, controlInbox,
                new ObservabilityMiddleware(), false, null);
    }

    public HarnessAgentFactory(AgentScopeModelFactory modelFactory, AgentStateStore agentStateStore,
                               RemoteFilesystemSpec filesystemSpec,
                               DefinitionWorkspaceMaterializer workspaceMaterializer,
                               RunControlInbox controlInbox,
                               ObservabilityMiddleware observabilityMiddleware,
                               boolean otelTracingEnabled) {
        this(modelFactory, agentStateStore, null, filesystemSpec, workspaceMaterializer, controlInbox,
                observabilityMiddleware, otelTracingEnabled, null);
    }

    public HarnessAgentFactory(AgentScopeModelFactory modelFactory, AgentStateStore agentStateStore,
                               DistributedStore distributedStore, RemoteFilesystemSpec filesystemSpec,
                               DefinitionWorkspaceMaterializer workspaceMaterializer,
                               RunControlInbox controlInbox,
                               ObservabilityMiddleware observabilityMiddleware,
                               boolean otelTracingEnabled) {
        this(modelFactory, agentStateStore, distributedStore, filesystemSpec, workspaceMaterializer,
                controlInbox, observabilityMiddleware, otelTracingEnabled, null);
    }

    public HarnessAgentFactory(AgentScopeModelFactory modelFactory, AgentStateStore agentStateStore,
                               DistributedStore distributedStore, RemoteFilesystemSpec filesystemSpec,
                               DefinitionWorkspaceMaterializer workspaceMaterializer,
                               RunControlInbox controlInbox,
                               ObservabilityMiddleware observabilityMiddleware,
                               boolean otelTracingEnabled,
                               NativeTeamExecutionGateway nativeTeamExecutionGateway) {
        this.modelFactory = Objects.requireNonNull(modelFactory);
        this.agentStateStore = Objects.requireNonNull(agentStateStore);
        this.distributedStore = distributedStore;
        this.filesystemSpec = filesystemSpec;
        this.workspaceMaterializer = Objects.requireNonNull(workspaceMaterializer);
        this.controlInbox = Objects.requireNonNull(controlInbox);
        this.observabilityMiddleware = observabilityMiddleware == null
                ? new ObservabilityMiddleware() : observabilityMiddleware;
        this.otelTracingEnabled = otelTracingEnabled;
        this.nativeTeamExecutionGateway = nativeTeamExecutionGateway;
    }

    public HarnessRuntimeTemplate build(RuntimeDefinitionSnapshot definition, HarnessTemplateKey key) {
        boolean autonomousTeamEnabled = definition.configuration().profile()
                == com.haizhuo.brain.runtime.api.model.RuntimeProfile.TEAM_AUTONOMOUS_READONLY;
        if (autonomousTeamEnabled && nativeTeamExecutionGateway == null)
            throw new IllegalStateException("Autonomous Team execution is not installed in this runtime");
        boolean legacy = definition.configuration().profile()
                == com.haizhuo.brain.runtime.api.model.RuntimeProfile.LEGACY_STABLE;
        if (!legacy && (filesystemSpec == null || !filesystemSpec.hasStore()
                || filesystemSpec.getIsolationScope() != IsolationScope.SESSION || distributedStore == null)) {
            throw new IllegalStateException(
                    "Non-legacy runtime profiles require a distributed store and session-scoped remote filesystem");
        }
        Toolkit toolkit = ExternalToolSchemaAssembler.assemble(definition.toolCatalog());
        Path definitionWorkspace = workspaceMaterializer.materialize(definition);
        Set<String> externalCatalogNames = definition.toolCatalog().stream()
                .map(RuntimeToolSchema::toolName)
                .collect(Collectors.toUnmodifiableSet());
        if (autonomousTeamEnabled) {
            if (externalCatalogNames.contains("run_readonly_team"))
                throw new IllegalStateException("run_readonly_team is a reserved native Team tool name");
            toolkit.registerTool(new RunReadonlyTeamTool(nativeTeamExecutionGateway, definition));
        }
        var teamPolicy = definition.configuration().team();
        boolean readonlyDelegationEnabled = definition.configuration().profile()
                == com.haizhuo.brain.runtime.api.model.RuntimeProfile.TEAM_READONLY
                && teamPolicy != null && !teamPolicy.allowedDelegations().isEmpty();
        boolean generalPurposeEnabled = readonlyDelegationEnabled && teamPolicy.allowedDelegations().stream()
                .anyMatch(rule -> "general-purpose".equals(rule.toRoleId()));
        boolean dynamicExpertsEnabled = readonlyDelegationEnabled && teamPolicy.allowedDelegations().stream()
                .anyMatch(rule -> "dynamic-expert".equals(rule.toRoleId()));
        AbstractFilesystem nativeFilesystem = !legacy ? filesystemSpec.toFilesystem(definitionWorkspace,
                "employee-version-" + definition.definitionVersionId(), new HaizhuoNamespaceFactory()) : null;
        DynamicSubagentFilesystem dynamicFilesystem = dynamicExpertsEnabled
                ? new DynamicSubagentFilesystem(nativeFilesystem) : null;
        String systemPrompt = definition.instructions();
        if (readonlyDelegationEnabled) {
            systemPrompt += "\n\n只读协作规则：子任务必须使用结构化 JSON，字段为 objective、required、deliverable "
                    + "(mediaType、contractVersion、requiredFields)、inputRefs 和 dependsOn。收到子结果后，"
                    + "先用 work_item_result 读取精确版本，再用 work_item_review 显式验收或要求修订；"
                    + "只有必需工作项均通过格式检查并被验收后才能给出最终答复。";
        }

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(definition.employeeName())
                .description("Haizhuo digital employee " + definition.employeeName())
                .sysPrompt(systemPrompt)
                .model(createModel(definition))
                .toolkit(toolkit)
                .workspace(definitionWorkspace)
                .stateStore(agentStateStore)
                .maxIters(definition.maxIterations())
                .middleware(new SecurityMiddleware())
                .middleware(new CapabilityViewMiddleware(externalCatalogNames))
                .middleware(new SessionBridgeMiddleware())
                .middleware(new RunControlMiddleware(controlInbox))
                .middleware(observabilityMiddleware)
                // P2：接入 Harness 计划能力。计划正文由计划工具的流式入参派生为
                // PLAN_SNAPSHOT 持久事件（见 AgentScopeEventTranslator），
                // 因此计划不需要另建存储，也能随会话游标一起续传。
                .enablePlanMode()
                .planFileDirectory(PLAN_DIRECTORY)
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
        if (readonlyDelegationEnabled) {
            List<SubagentEntry> entries = new java.util.ArrayList<>(definition.configuration().members().stream().map(member -> {
                if (member.definition() == null)
                    throw new IllegalStateException("Frozen expert runtime definition is missing for " + member.roleId());
                SubagentFactory factory = parent -> {
                    var parentCall = parent == null ? null
                            : parent.get(com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext.class);
                    String parentRole = parentCall == null ? null : parentCall.roleId();
                    boolean allowed = definition.configuration().team().allowedDelegations().stream()
                            .anyMatch(rule -> rule.fromRoleId().equals(parentRole)
                                    && rule.toRoleId().equals(member.roleId()));
                    if (!allowed) throw new SecurityException("fixed expert delegation is not allowed");
                    return buildReadonlyExpert(member, parent);
                };
                return new SubagentEntry(member.roleId(), "已发布只读专家：" + member.definition().employeeName(), factory);
            }).toList());
            if (generalPurposeEnabled) {
                SubagentFactory factory = parent -> {
                    var parentCall = parent == null ? null
                            : parent.get(com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext.class);
                    boolean allowed = parentCall != null && teamPolicy.allowedDelegations().stream().anyMatch(rule ->
                            rule.fromRoleId().equals(parentCall.roleId()) && rule.toRoleId().equals("general-purpose"));
                    if (!allowed) throw new SecurityException("general-purpose delegation is not allowed for this role");
                    return buildReadonlyGeneralPurpose(definition, definitionWorkspace, parent);
                };
                entries.add(new SubagentEntry("general-purpose", "已发布通用只读专家", factory));
            }
            if (dynamicExpertsEnabled) {
                DefaultAgentManager agentManager = new DefaultAgentManager(entries, null);
                DynamicSubagentsMiddleware nativeSubagents = new DynamicSubagentsMiddleware(entries,
                        dynamicFilesystem, definitionWorkspace, declaration -> {
                    if (!RunScopedDelegationBudget.isDynamicRole(declaration.getName()))
                        throw new SecurityException("dynamic expert name must use the dyn-* namespace");
                    if (declaration.getInlineAgentsBody() == null || declaration.getInlineAgentsBody().isBlank()
                            || declaration.getInlineAgentsBody().length() > 24_000)
                        throw new SecurityException("dynamic expert system prompt is empty or too large");
                    return parent -> buildReadonlyDynamicExpert(definition, definitionWorkspace, declaration, parent);
                }, agentManager, null, new SynchronousOnlyTaskRepository());
                nativeSubagents.getTools().forEach(toolkit::registerTool);
                toolkit.registerTool(new AgentGenerateTool(new SubagentSpecGenerator(createModel(definition)),
                        agentManager, dynamicFilesystem));
                builder.middleware(nativeSubagents);
            } else {
                SubagentsMiddleware nativeSubagents = new SubagentsMiddleware(entries, new SynchronousOnlyTaskRepository(),
                        (WorkspaceManager) null);
                nativeSubagents.getTools().forEach(toolkit::registerTool);
                builder.middleware(nativeSubagents);
            }
            builder.middleware(new DelegationAcceptanceMiddleware(dynamicFilesystem,
                    definition.configuration().policy().syncTimeoutSeconds()));
            for (String reserved : List.of("agent_spawn", "agent_send", "agent_generate", "work_item_result", "work_item_review")) {
                if (externalCatalogNames.contains(reserved))
                    throw new IllegalStateException("reserved native delegation tool name in external catalog: " + reserved);
            }
            toolkit.registerTool(new RunWorkItemTools());
        }
        if (otelTracingEnabled) {
            // AgentScope 2.0.3 原生中间件负责 invoke_agent/chat/execute_tool span；
            // 上面的平台中间件只建立稳定 Run 根 span并传播 Langfuse 属性。
            builder.middleware(new OtelTracingMiddleware());
        }
        if (!legacy) {
            builder.distributedStore(distributedStore)
                    .abstractFilesystem(new SessionWorkspaceFilesystem(nativeFilesystem));
        }
        if (readonlyDelegationEnabled) {
            // Native fixed experts are forced synchronous in RuntimeContextFactory. Prevent the
            // Harness workspace default bus from falling back to RuntimeContext.empty(), and do
            // not advertise its background-wait tool for this profile.
            SynchronousOnlyHarnessTransport transport = new SynchronousOnlyHarnessTransport();
            builder.messageBus(transport).asyncToolRegistry(transport);
        }
        HarnessAgent agent = builder.build();
        // Harness 2.0.3 has no web-tools disable flag; keep network access inside the published tool catalog.
        agent.getToolkit().removeTool("web_fetch");
        agent.getToolkit().removeTool("web_search");
        if (readonlyDelegationEnabled) {
            agent.getToolkit().removeTool("wait_async_results");
            agent.getToolkit().removeTool("task_output");
            agent.getToolkit().removeTool("task_list");
            agent.getToolkit().removeTool("task_cancel");
        }
        return new HarnessRuntimeTemplate(key, agent, Instant.now());
    }

    private HarnessAgent buildReadonlyExpert(
            com.haizhuo.brain.runtime.api.model.RuntimeEmployeeConfiguration.FixedMember member,
            io.agentscope.core.agent.RuntimeContext parent) {
        var definition = member.definition();
        Path workspace = workspaceMaterializer.materialize(definition);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(definition.employeeName())
                .agentId("expert-" + member.roleId() + "-v" + member.definitionVersionId())
                .description("Read-only fixed expert " + member.roleId())
                .sysPrompt(definition.instructions() + "\n\n你是只读专家，只能分析父任务并返回结果；不得执行外部业务动作、修改文件或继续委派。")
                .model(createModel(definition))
                .toolkit(new Toolkit())
                .workspace(workspace)
                .stateStore(agentStateStore)
                .maxIters(Math.min(4, Math.min(member.steps(), definition.maxIterations())))
                .middleware(new SecurityMiddleware())
                .middleware(new FixedExpertScopeMiddleware(member.roleId(), member.employeeId(),
                        member.definitionVersionId(), parent))
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
                .disableAtPathExpansion();
        SynchronousOnlyHarnessTransport transport = new SynchronousOnlyHarnessTransport();
        builder.messageBus(transport).asyncToolRegistry(transport);
        // Child experts read only the immutable materialized package through native skill loading.
        // They receive no filesystem tools or remote filesystem; the transport prevents Harness
        // from falling back to its workspace bus and exposing background-task controls.
        HarnessAgent agent = builder.build();
        removeReadonlyBackgroundTools(agent);
        agent.getToolkit().removeTool("web_fetch");
        agent.getToolkit().removeTool("web_search");
        return agent;
    }

    private HarnessAgent buildReadonlyGeneralPurpose(RuntimeDefinitionSnapshot definition, Path workspace,
                                                      io.agentscope.core.agent.RuntimeContext parent) {
        return buildReadonlyLeaf(definition, workspace, "general-purpose", "通用只读专家",
                "你是一个只读通用专家。只分析父任务明确提供的材料并返回结果；不得执行外部动作、修改文件或继续委派。",
                parent, Math.min(4, definition.maxIterations()));
    }

    private HarnessAgent buildReadonlyDynamicExpert(RuntimeDefinitionSnapshot definition, Path workspace,
                                                     SubagentDeclaration declaration,
                                                     io.agentscope.core.agent.RuntimeContext parent) {
        if (!RunScopedDelegationBudget.isDynamicRole(declaration.getName()))
            throw new SecurityException("dynamic expert name must use the dyn-* namespace");
        var parentCall = parent == null ? null
                : parent.get(com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext.class);
        boolean allowed = parentCall != null && definition.configuration().team().allowedDelegations().stream()
                .anyMatch(rule -> rule.fromRoleId().equals(parentCall.roleId())
                        && rule.toRoleId().equals("dynamic-expert"));
        if (!allowed) throw new SecurityException("dynamic expert delegation is not allowed for this role");
        String prompt = declaration.getInlineAgentsBody();
        if (prompt == null || prompt.isBlank() || prompt.length() > 24_000)
            throw new SecurityException("dynamic expert prompt is empty or exceeds its limit");
        return buildReadonlyLeaf(definition, workspace, declaration.getName(), declaration.getDescription(),
                prompt, parent, Math.min(4, Math.max(1, declaration.getSteps())));
    }

    private HarnessAgent buildReadonlyLeaf(RuntimeDefinitionSnapshot definition, Path workspace, String roleId,
                                           String description, String prompt,
                                           io.agentscope.core.agent.RuntimeContext parent, int steps) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(roleId)
                .agentId("readonly-" + roleId)
                .description(description == null || description.isBlank() ? "只读子 Agent" : description)
                .sysPrompt(prompt + "\n\n你是只读叶子 Agent。不得执行外部业务动作、调用工具、读写文件或继续委派。")
                .model(createModel(definition))
                .toolkit(new Toolkit())
                .workspace(workspace)
                .stateStore(agentStateStore)
                .maxIters(Math.min(4, Math.max(1, steps)))
                .middleware(new SecurityMiddleware())
                .middleware(new DelegatedChildScopeMiddleware(roleId, parent))
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
                .disableAtPathExpansion();
        SynchronousOnlyHarnessTransport transport = new SynchronousOnlyHarnessTransport();
        builder.messageBus(transport).asyncToolRegistry(transport);
        HarnessAgent agent = builder.build();
        removeReadonlyBackgroundTools(agent);
        agent.getToolkit().removeTool("web_fetch");
        agent.getToolkit().removeTool("web_search");
        return agent;
    }

    private static void removeReadonlyBackgroundTools(HarnessAgent agent) {
        for (String toolName : List.of("wait_async_results", "task_output", "task_list", "task_cancel"))
            agent.getToolkit().removeTool(toolName);
    }

    /** 供测试使用：单元测试可替换成不触网络的确定性模型。 */
    protected ChatModelBase createModel(RuntimeDefinitionSnapshot definition) {
        return modelFactory.create(definition);
    }
}
