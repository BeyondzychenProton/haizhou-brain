package com.haizhuo.brain.platform.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleRepository;
import com.haizhuo.brain.platform.mcp.McpCatalogRepository;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.HarnessRunSpecRepository;
import java.util.Map;
import java.util.Objects;

/**
 * 固定顺序的授权流水线（规格 §37）。顺序属于安全契约的一部分，不得重排；
 * 每一次业务拒绝都返回受控的、对模型安全的文案，而不是内部规则细节（§37/§89）。
 *
 * <pre>
 * 1  能力存在                   6  从参数推导 Resource
 * 2  能力已启用                  7  Resource + Action 策略
 * 3  Run 工具视图包含该工具       8  确认 / 审批策略
 * 4  用户处于激活状态             9  凭据可用性
 * 5  Subject / Role / Scope    10  执行
 * </pre>
 */
public class DefaultToolExecutionGatewayService implements ToolExecutionGatewayService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() { };

    private final AgentDefinitionRepository definitions;
    private final HarnessRunSpecRepository runSpecs;
    private final ToolExecutionUserDirectory users;
    private final ToolResourcePolicy resourcePolicy;
    private final CredentialResolver credentials;
    private final CapabilityExecutorRegistry executors;
    private final McpCatalogRepository mcpCatalog;
    private final HarnessDefinitionBundleRepository bundles;
    private final ToolApprovalRepository approvals;

    public DefaultToolExecutionGatewayService(AgentDefinitionRepository definitions,
                                              HarnessRunSpecRepository runSpecs,
                                              ToolExecutionUserDirectory users,
                                              ToolResourcePolicy resourcePolicy,
                                              CredentialResolver credentials,
                                              CapabilityExecutorRegistry executors) {
        this(definitions, runSpecs, users, resourcePolicy, credentials, executors, null, null, null);
    }

    public DefaultToolExecutionGatewayService(AgentDefinitionRepository definitions,
                                              HarnessRunSpecRepository runSpecs,
                                              ToolExecutionUserDirectory users,
                                              ToolResourcePolicy resourcePolicy,
                                              CredentialResolver credentials,
                                              CapabilityExecutorRegistry executors,
                                              McpCatalogRepository mcpCatalog,
                                              HarnessDefinitionBundleRepository bundles,
                                              ToolApprovalRepository approvals) {
        this.definitions = Objects.requireNonNull(definitions);
        this.runSpecs = Objects.requireNonNull(runSpecs);
        this.users = Objects.requireNonNull(users);
        this.resourcePolicy = Objects.requireNonNull(resourcePolicy);
        this.credentials = Objects.requireNonNull(credentials);
        this.executors = Objects.requireNonNull(executors);
        this.mcpCatalog = mcpCatalog;
        this.bundles = bundles;
        this.approvals = approvals;
    }

    @Override
    public ToolPreparation prepare(PlatformToolExecution execution, AgentRun run) {
        // 1 能力存在
        var capability = definitions.findCapabilityByRevisionId(execution.capabilityRevisionId());
        if (capability.isEmpty()) {
            return ToolPreparation.denied("CAPABILITY_NOT_FOUND", "该能力不存在或已下线。");
        }
        CapabilityCatalogEntry entry = capability.get();
        // 2 能力已启用（重新查询可覆盖目录读取之后发生的紧急停用）
        if (!definitions.isCapabilityEnabled(entry.capabilityCode(), entry.revision())) {
            return ToolPreparation.denied("CAPABILITY_DISABLED", "该能力当前不可用。");
        }
        // 3 Run 工具视图包含该工具 —— 冻结视图只能收窄、不能放宽（§11.2）
        var runSpec = runSpecs.findByRunId(execution.runId());
        if (runSpec.isEmpty() || !runSpec.get().modelVisibleToolNames().contains(execution.toolName())) {
            return ToolPreparation.denied("TOOL_OUT_OF_RUN_VIEW", "该操作不在本次运行的授权范围内。");
        }
        if (!entry.toolName().equals(execution.toolName()) || !belongsToPublishedBundle(execution, runSpec.get())) {
            return ToolPreparation.denied("TOOL_REVISION_MISMATCH", "该操作不属于本次运行的已发布工具修订。");
        }
        // 4 用户处于激活状态
        if (!users.isUserActive(run.userId().value())) {
            return ToolPreparation.denied("USER_INACTIVE", "当前账号不可用。");
        }
        // 5 Subject / Role / Scope —— P0 的作用域检查即用户能力授权
        boolean isMcp = entry.type() == CapabilityBinding.CapabilityType.MCP;
        if (!isMcp && !definitions.hasUserCapabilityGrant(run.userId().value(), entry.capabilityCode())) {
            return ToolPreparation.denied("CAPABILITY_NOT_GRANTED", "当前账号没有该能力的使用权限。");
        }
        // 6 + 7 Resource 推导 + Resource/Action 策略（平台侧，§38）
        Map<String, Object> arguments = parseArguments(execution.inputJson());
        if (isMcp) {
            if (!mcpSourceAvailable(entry))
                return ToolPreparation.denied("MCP_SOURCE_UNAVAILABLE", "该工具当前不可用。");
        } else {
            String resource = resourcePolicy.deriveResource(entry, arguments);
            if (!resourcePolicy.allows(run.userId(), entry, resource, arguments)) {
                return ToolPreparation.denied("RESOURCE_POLICY_DENIED", "该操作未获得授权。");
            }
        }
        // 8 确认 / 审批策略（§39）
        if (entry.requiresConfirmation() && !wasApproved(execution, run)) {
            return ToolPreparation.approvalRequired(entry);
        }
        // 9 凭据可用性 —— 解析出的值只留在进程内（§40）
        if (!isMcp && credentials.resolve(run.userId(), execution.capabilityRevisionId()) == null) {
            return ToolPreparation.denied("CREDENTIAL_UNAVAILABLE", "该操作缺少可用的执行凭据。");
        }
        return ToolPreparation.allowed(entry);
    }

    @Override
    public ToolExecutionResult execute(PlatformToolExecution execution, AgentRun run,
                                       ToolPreparation preparation) {
        if (preparation.outcome() == ToolPreparation.Outcome.DENIED) {
            return ToolExecutionResult.failed(preparation.denialCode(), preparation.safeMessage());
        }
        if (preparation.outcome() == ToolPreparation.Outcome.APPROVAL_REQUIRED) {
            throw new IllegalStateException("execution requires an approval decision before execute");
        }
        CapabilityCatalogEntry entry = preparation.capability();
        if (!definitions.isCapabilityEnabled(entry.capabilityCode(), entry.revision()))
            return ToolExecutionResult.failed("CAPABILITY_DISABLED", "该能力当前不可用。");
        if (!users.isUserActive(run.userId().value()))
            return ToolExecutionResult.failed("USER_INACTIVE", "当前账号不可用。");
        if (entry.type() == CapabilityBinding.CapabilityType.MCP && !mcpSourceAvailable(entry))
            return ToolExecutionResult.failed("MCP_SOURCE_UNAVAILABLE", "该工具当前不可用。");
        if (entry.requiresConfirmation() && !wasApproved(execution, run))
            return ToolExecutionResult.failed("APPROVAL_REQUIRED", "该操作尚未获确认。");
        try {
            // 在最后一刻重新解析；密钥绝不会离开这个调用栈帧（§40）。
            ResolvedCredential credential = preparation.capability().type() == CapabilityBinding.CapabilityType.MCP
                    ? ResolvedCredential.none()
                    : credentials.resolve(run.userId(), execution.capabilityRevisionId());
            CapabilityExecutor executor = executors.resolve(preparation.capability());
            Map<String, Object> arguments = parseArguments(execution.inputJson());
            ToolExecutionResult result = executor.execute(
                    new CapabilityExecutionContext(execution.runId(), run.userId(), execution,
                            preparation.capability(), credential), arguments);
            return result == null
                    ? ToolExecutionResult.unknown("操作结果未知，请核对业务状态后重试。")
                    : result;
        } catch (Exception error) {
            if (preparation.capability().type() == CapabilityBinding.CapabilityType.MCP
                    && "mcp.write".equals(preparation.capability().businessAction()))
                return ToolExecutionResult.unknown("写操作结果尚不明确，请核查后再操作。");
            return ToolExecutionResult.failed("TOOL_EXECUTION_ERROR", "操作执行失败，请稍后重试。");
        }
    }

    private boolean wasApproved(PlatformToolExecution execution, AgentRun run) {
        if (approvals == null) return false;
        return approvals.findByToolExecutionId(execution.id())
                .filter(approval -> approval.decision() == ApprovalState.APPROVED
                        && approval.runId().equals(run.id())
                        && approval.requestedFor().equals(run.userId()))
                .isPresent();
    }

    private boolean mcpSourceAvailable(CapabilityCatalogEntry entry) {
        if (mcpCatalog == null) return false;
        var source = mcpCatalog.findSource(entry.capabilityRevisionId());
        if (source.isEmpty()) return false;
        var connection = mcpCatalog.findConnection(source.get().connectionId());
        return connection.isPresent() && connection.get().enabled()
                && connection.get().revision() == source.get().connectionRevision();
    }

    private boolean belongsToPublishedBundle(PlatformToolExecution execution,
                                              com.haizhuo.brain.platform.run.HarnessRunSpec spec) {
        if (bundles == null) return true; // Compatibility constructor for pre-MCP isolated tests.
        return bundles.findByBundleHash(spec.definitionBundleHash())
                .filter(bundle -> bundle.definitionVersionId() == spec.definitionVersionId())
                .stream().flatMap(bundle -> bundle.toolCatalog().stream())
                .anyMatch(tool -> tool.capabilityRevisionId() == execution.capabilityRevisionId()
                        && tool.toolName().equals(execution.toolName()));
    }

    private static Map<String, Object> parseArguments(String inputJson) {
        try {
            Map<String, Object> parsed = JSON.readValue(inputJson, ARGUMENTS);
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (Exception error) {
            throw new IllegalArgumentException("tool input is not a valid JSON object", error);
        }
    }
}
