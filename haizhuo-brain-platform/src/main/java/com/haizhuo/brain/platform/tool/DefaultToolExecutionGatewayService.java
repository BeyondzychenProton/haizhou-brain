package com.haizhuo.brain.platform.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
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

    public DefaultToolExecutionGatewayService(AgentDefinitionRepository definitions,
                                              HarnessRunSpecRepository runSpecs,
                                              ToolExecutionUserDirectory users,
                                              ToolResourcePolicy resourcePolicy,
                                              CredentialResolver credentials,
                                              CapabilityExecutorRegistry executors) {
        this.definitions = Objects.requireNonNull(definitions);
        this.runSpecs = Objects.requireNonNull(runSpecs);
        this.users = Objects.requireNonNull(users);
        this.resourcePolicy = Objects.requireNonNull(resourcePolicy);
        this.credentials = Objects.requireNonNull(credentials);
        this.executors = Objects.requireNonNull(executors);
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
        // 4 用户处于激活状态
        if (!users.isUserActive(run.userId().value())) {
            return ToolPreparation.denied("USER_INACTIVE", "当前账号不可用。");
        }
        // 5 Subject / Role / Scope —— P0 的作用域检查即用户能力授权
        if (!definitions.hasUserCapabilityGrant(run.userId().value(), entry.capabilityCode())) {
            return ToolPreparation.denied("CAPABILITY_NOT_GRANTED", "当前账号没有该能力的使用权限。");
        }
        // 6 + 7 Resource 推导 + Resource/Action 策略（平台侧，§38）
        Map<String, Object> arguments = parseArguments(execution.inputJson());
        String resource = resourcePolicy.deriveResource(entry, arguments);
        if (!resourcePolicy.allows(run.userId(), entry, resource, arguments)) {
            return ToolPreparation.denied("RESOURCE_POLICY_DENIED", "该操作未获得授权。");
        }
        // 8 确认 / 审批策略（§39）
        if (entry.requiresConfirmation()) {
            return ToolPreparation.approvalRequired(entry);
        }
        // 9 凭据可用性 —— 解析出的值只留在进程内（§40）
        if (credentials.resolve(run.userId(), execution.capabilityRevisionId()) == null) {
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
        try {
            // 在最后一刻重新解析；密钥绝不会离开这个调用栈帧（§40）。
            ResolvedCredential credential = credentials.resolve(run.userId(), execution.capabilityRevisionId());
            CapabilityExecutor executor = executors.resolve(preparation.capability());
            Map<String, Object> arguments = parseArguments(execution.inputJson());
            ToolExecutionResult result = executor.execute(
                    new CapabilityExecutionContext(execution.runId(), run.userId(), execution,
                            preparation.capability(), credential), arguments);
            return result == null
                    ? ToolExecutionResult.unknown("操作结果未知，请核对业务状态后重试。")
                    : result;
        } catch (Exception error) {
            return ToolExecutionResult.failed("TOOL_EXECUTION_ERROR", "操作执行失败，请稍后重试。");
        }
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
