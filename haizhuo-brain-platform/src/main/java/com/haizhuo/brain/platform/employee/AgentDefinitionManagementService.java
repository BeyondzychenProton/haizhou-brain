package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionPublisher;
import com.haizhuo.brain.platform.tool.CapabilityExecutorRegistry;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class AgentDefinitionManagementService {
    private static final Set<String> MODEL_PROVIDERS = Set.of("openai", "openai-compatible", "dashscope");
    private static final Set<RuntimeProfile> MEMBER_PROFILES = Set.of(RuntimeProfile.LEGACY_STABLE,
            RuntimeProfile.SINGLE_SKILLED);
    private static final String ROLE_ID = "[a-z][a-z0-9_-]{0,31}";

    private final AgentDefinitionRepository repository;
    private final CapabilityExecutorRegistry executors;
    private final HarnessDefinitionPublisher publisher;
    private final CapabilityAssetRepository assets;
    private final RuntimeProfileAdmissionPolicy profileAdmission;

    /** Compatibility constructor: old callers can continue using the legacy runtime only. */
    public AgentDefinitionManagementService(AgentDefinitionRepository repository,
                                            CapabilityExecutorRegistry executors,
                                            HarnessDefinitionPublisher publisher) {
        this(repository, executors, publisher, null, RuntimeProfileAdmissionPolicy.legacyOnly());
    }

    public AgentDefinitionManagementService(AgentDefinitionRepository repository,
                                            CapabilityExecutorRegistry executors,
                                            HarnessDefinitionPublisher publisher,
                                            CapabilityAssetRepository assets,
                                            Set<RuntimeProfile> enabledProfiles) {
        this(repository, executors, publisher, assets, RuntimeProfileAdmissionPolicy.configuredOnly(enabledProfiles));
    }

    public AgentDefinitionManagementService(AgentDefinitionRepository repository,
                                            CapabilityExecutorRegistry executors,
                                            HarnessDefinitionPublisher publisher,
                                            CapabilityAssetRepository assets,
                                            RuntimeProfileAdmissionPolicy profileAdmission) {
        this.repository = Objects.requireNonNull(repository);
        this.executors = Objects.requireNonNull(executors);
        this.publisher = Objects.requireNonNull(publisher);
        this.assets = assets;
        this.profileAdmission = Objects.requireNonNull(profileAdmission);
    }

    public List<CapabilityCatalogEntry> listCapabilities() { return repository.listCapabilities(); }

    public List<UserCapabilityGrant> listUserCapabilityGrants(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return repository.listUserCapabilityGrants(userId);
    }

    public AgentDefinitionDraft getDraft(long employeeId) {
        return repository.findDraft(employeeId).orElseThrow(() -> new IllegalArgumentException("Agent draft not found"));
    }

    public AgentDefinitionDraft saveDraft(long employeeId, int expectedRevision, DraftUpdate update, long actorId, String reason) {
        return saveDraft(employeeId, expectedRevision, update, actorId, null, reason);
    }

    /** 恢复等幂等管理员命令可以在审计记录中保留命令键。 */
    public AgentDefinitionDraft saveDraft(long employeeId, int expectedRevision, DraftUpdate update, long actorId,
                                          String requestId, String reason) {
        Objects.requireNonNull(update, "update");
        if (update.instructions() == null || update.instructions().isBlank() || update.instructions().length() > 12000)
            throw new IllegalArgumentException("instructions must be between 1 and 12000 characters");
        if (!MODEL_PROVIDERS.contains(update.modelProvider()))
            throw new IllegalArgumentException("Unsupported model provider");
        if (update.modelName() == null || update.modelName().isBlank() || update.modelName().length() > 128)
            throw new IllegalArgumentException("modelName is required and must be at most 128 characters");
        if (update.capabilities() == null)
            throw new IllegalArgumentException("capabilities must be provided; use an empty list when none are selected");

        AgentDefinitionDraft previous = getDraft(employeeId);
        EmployeeRuntimeConfiguration configuration = update.configuration() == null
                ? previous.configuration() : update.configuration();
        if (configuration.profile() == RuntimeProfile.LEGACY_STABLE && update.capabilities().isEmpty())
            throw new IllegalArgumentException("At least one capability must be selected for LEGACY_STABLE");

        Set<String> unique = new HashSet<>();
        for (CapabilitySelection selection : update.capabilities()) {
            if (selection == null || selection.capabilityCode() == null || selection.revision() == null
                    || !unique.add(selection.capabilityCode()))
                throw new IllegalArgumentException("Capability selections must be complete and unique");
            CapabilityCatalogEntry entry = repository.findCapability(selection.capabilityCode(), selection.revision())
                    .orElseThrow(() -> new IllegalArgumentException("Capability revision does not exist"));
            if (isAsset(entry.type())) {
                if (configuration.profile() == RuntimeProfile.LEGACY_STABLE)
                    throw new IllegalArgumentException("LEGACY_STABLE cannot select skill or knowledge assets");
                CapabilityAssetRevision asset = assets == null ? null
                        : assets.findRevisionByCodeAndRevision(selection.capabilityCode(), selection.revision()).orElse(null);
                if (asset == null || asset.type() != entry.type())
                    throw new IllegalArgumentException("Approved skill or knowledge revision does not exist");
            } else if (entry.type() != CapabilityBinding.CapabilityType.TOOL
                    && entry.type() != CapabilityBinding.CapabilityType.MCP) {
                throw new IllegalArgumentException("Unsupported capability type: " + entry.type());
            }
        }
        return repository.saveDraft(employeeId, expectedRevision, update.instructions().trim(),
                update.modelProvider(), update.modelName().trim(), update.capabilities(), configuration,
                AgentDefinitionManagementAudit.pending(actorId, "DRAFT_SAVED", "DIGITAL_EMPLOYEE",
                        String.valueOf(employeeId), requestId, reason));
    }

    public ValidationResult validateDraft(long employeeId) {
        AgentDefinitionDraft draft = getDraft(employeeId);
        List<ValidationIssue> issues = new ArrayList<>();
        EmployeeRuntimeConfiguration configuration = draft.configuration();
        RuntimeProfile profile = configuration.profile();
        RuntimeProfileAdmissionPolicy.Admission admission = profileAdmission.evaluate(profile);
        if (!admission.enabled()) {
            String reason = admission.reasonCode() == null ? "PROFILE_DISABLED" : admission.reasonCode();
            String message = "PROFILE_VERIFICATION_MISSING".equals(reason)
                    ? "该运行配置缺少与当前部署版本和环境匹配的验收证据，当前不能发布"
                    : "该运行配置尚未启用，当前不能发布";
            issues.add(issue(reason, "configuration.profile", message, profile.name()));
        }
        validateRuntimeConfiguration(employeeId, configuration, issues);

        if (draft.capabilities().isEmpty() && profile == RuntimeProfile.LEGACY_STABLE)
            issues.add(issue("NO_CAPABILITIES", "capabilities", "LEGACY_STABLE 至少选择一项工具或 MCP 能力", null));
        for (int i = 0; i < draft.capabilities().size(); i++) {
            CapabilitySelection selection = draft.capabilities().get(i);
            String field = "capabilities[" + i + "]";
            CapabilityCatalogEntry entry = repository.findCapability(selection.capabilityCode(), selection.revision()).orElse(null);
            if (entry == null) {
                issues.add(issue("REVISION_MISSING", field,
                        selection.capabilityCode() + " 的修订不存在", selection.capabilityCode() + "@" + selection.revision()));
                continue;
            }
            if (!entry.enabled() || !repository.isCapabilityEnabled(entry.capabilityCode(), entry.revision()))
                issues.add(issue("CAPABILITY_DISABLED", field, selection.capabilityCode() + " 当前已停用", selection.capabilityCode()));
            if (isAsset(entry.type())) {
                if (profile == RuntimeProfile.LEGACY_STABLE) {
                    issues.add(issue("ASSET_REQUIRES_V2", field, "技能和知识资产需要显式选择 V2 运行配置", selection.capabilityCode()));
                }
                CapabilityAssetRevision asset = assets == null ? null
                        : assets.findRevisionByCodeAndRevision(selection.capabilityCode(), selection.revision()).orElse(null);
                if (asset == null || asset.type() != entry.type())
                    issues.add(issue("ASSET_REVISION_MISSING", field, "审核资产修订不存在或类型不匹配", selection.capabilityCode()));
                continue;
            }
            if (entry.type() != CapabilityBinding.CapabilityType.TOOL
                    && entry.type() != CapabilityBinding.CapabilityType.MCP) {
                issues.add(issue("CAPABILITY_TYPE_UNSUPPORTED", field, "该能力类型不能进入员工运行包", selection.capabilityCode()));
                continue;
            }
            if (!executors.supports(entry.implementationKey(), entry.capabilityCode()))
                issues.add(issue("PROVIDER_UNAVAILABLE", field, selection.capabilityCode() + " 没有可用的运行时提供者", selection.capabilityCode()));
            if (entry.toolName() == null || entry.toolName().isBlank() || entry.inputSchema().isEmpty())
                issues.add(issue("TOOL_SCHEMA_INVALID", field, selection.capabilityCode() + " 缺少工具名称或参数 Schema", selection.capabilityCode()));
        }
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues), draft.draftRevision(), previewHash(draft));
    }

    private void validateRuntimeConfiguration(long employeeId, EmployeeRuntimeConfiguration configuration,
                                              List<ValidationIssue> issues) {
        if (configuration.schemaVersion() != (configuration.profile() == RuntimeProfile.LEGACY_STABLE ? 1 : 2))
            issues.add(issue("CONFIG_SCHEMA_UNSUPPORTED", "configuration.schemaVersion", "配置版本与运行 profile 不匹配", null));

        EmployeeRuntimeConfiguration.RuntimePolicy policy = configuration.runtimePolicy();
        if (policy.maxIterations() < 1 || policy.maxIterations() > 20)
            issues.add(issue("ITERATION_LIMIT_INVALID", "configuration.runtimePolicy.maxIterations", "迭代次数必须在 1 到 20 之间", null));
        if (policy.maxParallelDelegations() < 0 || policy.maxParallelDelegations() > 2)
            issues.add(issue("PARALLEL_DELEGATION_LIMIT_INVALID", "configuration.runtimePolicy.maxParallelDelegations", "并行委派数必须在 0 到 2 之间", null));
        if (policy.maxExpertInvocationsPerRun() < 0 || policy.maxExpertInvocationsPerRun() > 4)
            issues.add(issue("EXPERT_INVOCATION_LIMIT_INVALID", "configuration.runtimePolicy.maxExpertInvocationsPerRun", "专家调用数必须在 0 到 4 之间", null));
        if (policy.syncTimeoutSeconds() < 1 || policy.syncTimeoutSeconds() > 120)
            issues.add(issue("SYNC_TIMEOUT_INVALID", "configuration.runtimePolicy.syncTimeoutSeconds", "同步超时必须在 1 到 120 秒之间", null));
        if (policy.memoryEnabled())
            issues.add(issue("MEMORY_NOT_APPROVED", "configuration.runtimePolicy.memoryEnabled", "当前 profile 不允许启用可写记忆", null));

        boolean teamProfile = configuration.profile() == RuntimeProfile.TEAM_READONLY
                || configuration.profile() == RuntimeProfile.TEAM_AUTONOMOUS_READONLY;
        if (!teamProfile) {
            if (configuration.team() != null)
                issues.add(issue("TEAM_CONFIG_NOT_ALLOWED", "configuration.team", "该 profile 不接受 Team 配置", null));
            if (!configuration.members().isEmpty())
                issues.add(issue("MEMBERS_NOT_ALLOWED", "configuration.members", "该 profile 不接受固定 Team 成员", null));
            return;
        }

        EmployeeRuntimeConfiguration.TeamConfiguration team = configuration.team();
        List<EmployeeRuntimeConfiguration.FixedMember> members = configuration.members();
        if (team == null) {
            issues.add(issue("TEAM_CONFIG_REQUIRED", "configuration.team", "Team profile 必须配置角色与委派策略", null));
            return;
        }
        if (members.isEmpty())
            issues.add(issue("TEAM_MEMBERS_REQUIRED", "configuration.members", "Team profile 至少需要一个固定成员", null));
        int memberLimit = configuration.profile() == RuntimeProfile.TEAM_AUTONOMOUS_READONLY ? 2 : 8;
        if (members.size() > memberLimit)
            issues.add(issue("TEAM_MEMBER_LIMIT_EXCEEDED", "configuration.members",
                    memberLimit == 2 ? "自治 Team 固定成员最多 2 个" : "固定成员最多 8 个", null));

        Set<String> roles = new HashSet<>();
        Set<Long> employeeIds = new HashSet<>();
        for (int i = 0; i < members.size(); i++) {
            EmployeeRuntimeConfiguration.FixedMember member = members.get(i);
            String field = "configuration.members[" + i + "]";
            validateRoleId(member.roleId(), field + ".roleId", issues);
            if (!roles.add(member.roleId()))
                issues.add(issue("ROLE_ID_DUPLICATE", field + ".roleId", "角色标识不能重复", member.roleId()));
            if (!employeeIds.add(member.employeeId()))
                issues.add(issue("MEMBER_DUPLICATE", field + ".employeeId", "同一个员工版本不能重复加入 Team", String.valueOf(member.employeeId())));
            if (member.steps() < 1 || member.steps() > 8)
                issues.add(issue("MEMBER_STEP_LIMIT_INVALID", field + ".steps", "成员步数必须在 1 到 8 之间", member.roleId()));
            if (member.employeeId() == employeeId)
                issues.add(issue("TEAM_SELF_REFERENCE", field + ".employeeId", "员工不能把自身加入为固定成员", String.valueOf(employeeId)));
            validateMemberVersion(employeeId, member, field, issues);
        }

        if (team.defaultRoleId() == null || !roles.contains(team.defaultRoleId()))
            issues.add(issue("DEFAULT_ROLE_MISSING", "configuration.team.defaultRoleId", "默认角色必须对应一个固定成员", team.defaultRoleId()));
        Set<String> selectable = new HashSet<>();
        for (int i = 0; i < team.userSelectableRoles().size(); i++) {
            String role = team.userSelectableRoles().get(i);
            validateRoleId(role, "configuration.team.userSelectableRoles[" + i + "]", issues);
            if (!selectable.add(role))
                issues.add(issue("SELECTABLE_ROLE_DUPLICATE", "configuration.team.userSelectableRoles[" + i + "]", "用户可选角色不能重复", role));
            if (!roles.contains(role))
                issues.add(issue("SELECTABLE_ROLE_UNKNOWN", "configuration.team.userSelectableRoles[" + i + "]", "用户可选角色必须对应一个固定成员", role));
        }
        Set<String> delegations = new HashSet<>();
        for (int i = 0; i < team.allowedDelegations().size(); i++) {
            EmployeeRuntimeConfiguration.RoleDelegation delegation = team.allowedDelegations().get(i);
            String field = "configuration.team.allowedDelegations[" + i + "]";
            if (delegation.fromRoleId() == null || !("coordinator".equals(delegation.fromRoleId())
                    || roles.contains(delegation.fromRoleId())))
                issues.add(issue("DELEGATION_SOURCE_UNKNOWN", field + ".fromRoleId", "委派来源必须是协调者或固定成员角色", delegation.fromRoleId()));
            if (!"coordinator".equals(delegation.fromRoleId()))
                issues.add(issue("DELEGATION_SOURCE_NOT_SUPPORTED", field + ".fromRoleId",
                        "当前运行工厂仅支持协调者发起委派", delegation.fromRoleId()));
            boolean reservedTarget = "general-purpose".equals(delegation.toRoleId())
                    || "dynamic-expert".equals(delegation.toRoleId());
            if (reservedTarget && configuration.profile() != RuntimeProfile.TEAM_READONLY)
                issues.add(issue("RESERVED_TARGET_PROFILE_UNSUPPORTED", field + ".toRoleId",
                        "通用只读与动态只读目标仅支持 TEAM_READONLY", delegation.toRoleId()));
            if (!reservedTarget && (delegation.toRoleId() == null || !roles.contains(delegation.toRoleId())))
                issues.add(issue("DELEGATION_TARGET_UNKNOWN", field + ".toRoleId", "委派目标必须是固定成员角色", delegation.toRoleId()));
            if (Objects.equals(delegation.fromRoleId(), delegation.toRoleId()))
                issues.add(issue("DELEGATION_SELF_REFERENCE", field, "角色不能委派给自身", delegation.fromRoleId()));
            String key = String.valueOf(delegation.fromRoleId()) + "->" + delegation.toRoleId();
            if (!delegations.add(key))
                issues.add(issue("DELEGATION_DUPLICATE", field, "委派规则不能重复", key));
        }
        if (policy.maxExpertInvocationsPerRun() < 1)
            issues.add(issue("TEAM_EXPERT_BUDGET_REQUIRED", "configuration.runtimePolicy.maxExpertInvocationsPerRun", "Team profile 至少允许一次专家调用", null));
    }

    private void validateMemberVersion(long ownerEmployeeId, EmployeeRuntimeConfiguration.FixedMember member,
                                       String field, List<ValidationIssue> issues) {
        if (member.employeeId() <= 0 || member.definitionVersionId() <= 0) {
            issues.add(issue("MEMBER_VERSION_INVALID", field, "成员必须固定到有效员工与发布版本", null));
            return;
        }
        DigitalEmployee owner = repository.findEmployee(ownerEmployeeId).orElse(null);
        if (owner == null) {
            issues.add(issue("OWNER_EMPLOYEE_MISSING", "employeeId", "员工身份不存在，无法校验成员租户", String.valueOf(ownerEmployeeId)));
            return;
        }
        DigitalEmployee memberEmployee = repository.findEmployee(member.employeeId()).orElse(null);
        if (memberEmployee == null || !memberEmployee.enabled()) {
            issues.add(issue("MEMBER_EMPLOYEE_UNAVAILABLE", field + ".employeeId", "固定成员不存在或已停用", String.valueOf(member.employeeId())));
            return;
        }
        TenantId tenantId = owner.tenantId();
        if (!memberEmployee.tenantId().equals(tenantId)) {
            issues.add(issue("MEMBER_TENANT_MISMATCH", field + ".employeeId", "固定成员必须与主员工属于同一租户", String.valueOf(member.employeeId())));
            return;
        }
        PublishedEmployee published = repository.findPublishedVersion(tenantId, member.employeeId(), member.definitionVersionId()).orElse(null);
        if (published == null || published.definition().id() != member.definitionVersionId()) {
            issues.add(issue("MEMBER_VERSION_NOT_FOUND", field + ".definitionVersionId", "指定的员工发布版本不存在", String.valueOf(member.definitionVersionId())));
            return;
        }
        if (!MEMBER_PROFILES.contains(published.definition().configuration().profile()))
            issues.add(issue("MEMBER_PROFILE_UNSUPPORTED", field + ".definitionVersionId", "固定成员只能使用 LEGACY_STABLE 或 SINGLE_SKILLED", String.valueOf(member.definitionVersionId())));
        else if (!profileAdmission.evaluate(published.definition().configuration().profile()).enabled())
            issues.add(issue("MEMBER_PROFILE_DISABLED", field + ".definitionVersionId",
                    "固定成员的运行配置未通过当前部署准入", String.valueOf(member.definitionVersionId())));
    }

    private static void validateRoleId(String roleId, String field, List<ValidationIssue> issues) {
        if (roleId == null || !roleId.matches(ROLE_ID)) {
            issues.add(issue("ROLE_ID_INVALID", field, "角色标识必须是小写字母开头的短标识", roleId));
        } else if ("coordinator".equals(roleId) || "general-purpose".equals(roleId)
                || "dynamic-expert".equals(roleId) || roleId.startsWith("dyn-")) {
            issues.add(issue("ROLE_ID_RESERVED", field, "该角色标识由运行时保留，不能作为固定成员角色", roleId));
        }
    }

    private static boolean isAsset(CapabilityBinding.CapabilityType type) {
        return type == CapabilityBinding.CapabilityType.SKILL || type == CapabilityBinding.CapabilityType.KNOWLEDGE;
    }

    private static ValidationIssue issue(String code, String fieldPath, String message, String relatedId) {
        return new ValidationIssue(code, "ERROR", fieldPath, message, relatedId);
    }

    private static String previewHash(AgentDefinitionDraft draft) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("employeeId", draft.employeeId());
        preview.put("draftRevision", draft.draftRevision());
        preview.put("instructions", draft.instructions());
        preview.put("modelProvider", draft.modelProvider());
        preview.put("modelName", draft.modelName());
        preview.put("capabilities", draft.capabilities().stream()
                .map(item -> Map.of("capabilityCode", item.capabilityCode(), "revision", item.revision())).toList());
        preview.put("configuration", configurationProjection(draft.configuration()));
        return CanonicalJson.sha256(preview);
    }

    private static Map<String, Object> configurationProjection(EmployeeRuntimeConfiguration configuration) {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("schemaVersion", configuration.schemaVersion());
        projection.put("profile", configuration.profile().name());
        EmployeeRuntimeConfiguration.RuntimePolicy policy = configuration.runtimePolicy();
        projection.put("runtimePolicy", Map.of(
                "maxIterations", policy.maxIterations(),
                "maxParallelDelegations", policy.maxParallelDelegations(),
                "maxExpertInvocationsPerRun", policy.maxExpertInvocationsPerRun(),
                "syncTimeoutSeconds", policy.syncTimeoutSeconds(),
                "memoryEnabled", policy.memoryEnabled()));
        EmployeeRuntimeConfiguration.TeamConfiguration team = configuration.team();
        if (team == null) {
            projection.put("team", null);
        } else {
            projection.put("team", Map.of(
                    "defaultRoleId", team.defaultRoleId() == null ? "" : team.defaultRoleId(),
                    "userSelectableRoles", team.userSelectableRoles(),
                    "allowedDelegations", team.allowedDelegations().stream()
                            .map(item -> Map.of("fromRoleId", Objects.toString(item.fromRoleId(), ""),
                                    "toRoleId", Objects.toString(item.toRoleId(), ""))).toList()));
        }
        projection.put("members", configuration.members().stream().map(member -> Map.of(
                "roleId", Objects.toString(member.roleId(), ""), "employeeId", member.employeeId(),
                "definitionVersionId", member.definitionVersionId(), "steps", member.steps())).toList());
        return projection;
    }

    public PublishedEmployee publish(long employeeId, int expectedRevision, String requestId, long actorId, String reason) {
        ValidationResult validation = validateDraft(employeeId);
        if (!validation.publishable()) throw new DefinitionNotPublishableException(validation);
        // Tx-01：版本插入、运行时包编译与当前版本指针切换由 publisher 置于同一事务边界。
        return publisher.publish(employeeId, expectedRevision,
                AgentDefinitionManagementAudit.pending(actorId, "DEFINITION_PUBLISHED", "DIGITAL_EMPLOYEE",
                        String.valueOf(employeeId), requestId, reason));
    }

    public void setCapabilityEnabled(String capabilityCode, boolean enabled, long actorId, String reason) {
        if (repository.listCapabilities().stream().noneMatch(entry -> entry.capabilityCode().equals(capabilityCode)))
            throw new IllegalArgumentException("Capability does not exist");
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("A reason of at most 500 characters is required");
        repository.setCapabilityEnabled(capabilityCode, enabled,
                AgentDefinitionManagementAudit.pending(actorId, "CAPABILITY_STATUS_CHANGED", "CAPABILITY",
                        capabilityCode, null, reason));
    }

    public void setUserCapabilityGrant(long userId, String capabilityCode, boolean enabled, long actorId, String reason) {
        if (userId <= 0 || actorId <= 0) throw new IllegalArgumentException("User and actor IDs must be positive");
        if (repository.listCapabilities().stream().noneMatch(entry -> entry.capabilityCode().equals(capabilityCode)))
            throw new IllegalArgumentException("Capability does not exist");
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("A reason of at most 500 characters is required");
        repository.setUserCapabilityGrant(userId, capabilityCode, enabled,
                AgentDefinitionManagementAudit.pending(actorId, "USER_CAPABILITY_GRANT_CHANGED", "PLATFORM_USER",
                        String.valueOf(userId), null, reason));
    }

    public record DraftUpdate(String instructions, String modelProvider, String modelName,
                              List<CapabilitySelection> capabilities, EmployeeRuntimeConfiguration configuration) {
        public DraftUpdate {
            if (capabilities != null) capabilities = List.copyOf(capabilities);
        }
        /** Old API clients omit configuration; preserve the saved profile instead of resetting it. */
        public DraftUpdate(String instructions, String modelProvider, String modelName,
                           List<CapabilitySelection> capabilities) {
            this(instructions, modelProvider, modelName, capabilities, null);
        }
    }

    public record ValidationIssue(String code, String severity, String fieldPath, String message, String relatedId) {
        public ValidationIssue(String code, String message) { this(code, "ERROR", null, message, null); }
    }
    public record ValidationResult(boolean publishable, List<ValidationIssue> issues, int draftRevision, String previewHash) {
        public ValidationResult { issues = List.copyOf(issues); }
        public ValidationResult(boolean publishable, List<ValidationIssue> issues, int draftRevision) {
            this(publishable, issues, draftRevision, "");
        }
    }
    public static final class DefinitionNotPublishableException extends RuntimeException {
        private final ValidationResult validation;
        public DefinitionNotPublishableException(ValidationResult validation) {
            super("Agent draft is not publishable");
            this.validation = validation;
        }
        public ValidationResult validation() { return validation; }
    }
}
