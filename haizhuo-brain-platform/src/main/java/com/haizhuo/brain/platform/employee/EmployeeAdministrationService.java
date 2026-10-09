package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.CreatedEmployee;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeePage;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeeSummary;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeeVersionDetail;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeeVersionPage;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 面向管理员的员工元数据、运行选项和不可变版本操作。 */
public class EmployeeAdministrationService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final List<RuntimeProfile> PROFILES = List.of(RuntimeProfile.values());

    private final EmployeeAdministrationStore store;
    private final EmployeeAdminCommandExecutor commands;
    private final AgentDefinitionManagementService definitions;
    private final RuntimeProfileAdmissionPolicy profileAdmission;

    public EmployeeAdministrationService(EmployeeAdministrationStore store, EmployeeAdminCommandExecutor commands,
                                         AgentDefinitionManagementService definitions,
                                         EmployeeRuntimeProfileSettings profileSettings) {
        this(store, commands, definitions, profileSettings,
                RuntimeProfileAdmissionPolicy.configuredOnly(profileSettings.enabledProfiles()));
    }

    public EmployeeAdministrationService(EmployeeAdministrationStore store, EmployeeAdminCommandExecutor commands,
                                         AgentDefinitionManagementService definitions,
                                         EmployeeRuntimeProfileSettings profileSettings,
                                         RuntimeProfileAdmissionPolicy profileAdmission) {
        this.store = Objects.requireNonNull(store);
        this.commands = Objects.requireNonNull(commands);
        this.definitions = Objects.requireNonNull(definitions);
        Objects.requireNonNull(profileSettings);
        this.profileAdmission = Objects.requireNonNull(profileAdmission);
    }

    public EmployeePage listEmployees(String query, Boolean enabled, Boolean published, String cursor, Integer limit) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() > 128) throw new IllegalArgumentException("query must be at most 128 characters");
        return store.listEmployees(normalized, enabled, published, cursor, normalizeLimit(limit));
    }

    public CreatedEmployee createEmployee(String employeeCode, String displayName, String requestId,
                                          long actorId, String reason) {
        String code = required(employeeCode, 64, "employeeCode");
        String name = required(displayName, 128, "displayName");
        requireRequest(requestId, reason, actorId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employeeCode", code);
        payload.put("displayName", name);
        payload.put("reason", reason.trim());
        return store.createEmployee(code, name, actorId, requestId, CanonicalJson.sha256(payload), reason);
    }

    public EmployeeSummary updateEmployee(long employeeId, long expectedRowVersion, String displayName,
                                          String requestId, long actorId, String reason) {
        if (employeeId <= 0 || expectedRowVersion < 0) throw new IllegalArgumentException("Invalid employee or row version");
        String name = required(displayName, 128, "displayName");
        requireRequest(requestId, reason, actorId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expectedRowVersion", expectedRowVersion);
        payload.put("displayName", name);
        payload.put("reason", reason.trim());
        return store.updateEmployee(employeeId, expectedRowVersion, name, actorId, requestId,
                CanonicalJson.sha256(payload), reason);
    }

    public EmployeeSummary setEmployeeEnabled(long employeeId, long expectedRowVersion, boolean enabled,
                                              String requestId, long actorId, String reason) {
        if (employeeId <= 0 || expectedRowVersion < 0) throw new IllegalArgumentException("Invalid employee or row version");
        requireRequest(requestId, reason, actorId);
        if (enabled) validateEnablement(employeeId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expectedRowVersion", expectedRowVersion);
        payload.put("enabled", enabled);
        payload.put("reason", reason.trim());
        return store.setEmployeeEnabled(employeeId, expectedRowVersion, enabled, actorId, requestId,
                CanonicalJson.sha256(payload), reason);
    }

    public EmployeeVersionPage listVersions(long employeeId, String cursor, Integer limit) {
        if (employeeId <= 0) throw new IllegalArgumentException("employeeId must be positive");
        return store.listVersions(employeeId, cursor, normalizeLimit(limit));
    }

    public EmployeeVersionDetail findVersion(long employeeId, long versionId) {
        if (employeeId <= 0 || versionId <= 0) throw new IllegalArgumentException("Employee and version IDs must be positive");
        return store.findVersion(employeeId, versionId)
                .orElseThrow(() -> new IllegalArgumentException("Employee version was not found"));
    }

    public AgentDefinitionDraft restoreDraft(long employeeId, long sourceVersionId, int expectedDraftRevision,
                                             String requestId, long actorId, String reason) {
        if (employeeId <= 0 || sourceVersionId <= 0 || expectedDraftRevision < 1)
            throw new IllegalArgumentException("Invalid employee, version, or draft revision");
        requireRequest(requestId, reason, actorId);
        EmployeeVersionDetail source = findVersion(employeeId, sourceVersionId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceVersionId", sourceVersionId);
        payload.put("expectedDraftRevision", expectedDraftRevision);
        payload.put("reason", reason.trim());
        EmployeeAdminCommandExecutor.Command command = new EmployeeAdminCommandExecutor.Command(actorId,
                "RESTORE_DRAFT", employeeId, requestId, CanonicalJson.sha256(payload));
        return commands.execute(command, AgentDefinitionDraft.class, () -> definitions.saveDraft(employeeId,
                expectedDraftRevision, new AgentDefinitionManagementService.DraftUpdate(source.instructions(),
                        source.modelProvider(), source.modelName(), source.capabilities().stream()
                        .map(capability -> new CapabilitySelection(capability.capabilityCode(), capability.revision())).toList(),
                        source.configuration()), actorId, requestId, reason));
    }

    public RuntimeOptions runtimeOptions() {
        List<ProfileOption> profiles = PROFILES.stream().map(profile -> {
            RuntimeProfileAdmissionPolicy.Admission admission = profileAdmission.evaluate(profile);
            return new ProfileOption(profile, profile == RuntimeProfile.LEGACY_STABLE ? 1 : 2,
                    admission.configuredEnabled(), admission.enabled(), admission.reasonCode(), memberLimit(profile));
        }).toList();
        List<ReservedDelegationTarget> reserved = List.of(
                new ReservedDelegationTarget("BUILTIN_GENERAL_PURPOSE", "general-purpose", true,
                        List.of(RuntimeProfile.TEAM_READONLY), "TEAM_READONLY"),
                new ReservedDelegationTarget("DYNAMIC_EXPERT", "dynamic-expert", true,
                        List.of(RuntimeProfile.TEAM_READONLY), "TEAM_READONLY"));
        return new RuntimeOptions(profiles,
                new IntegerRange(1, 20), new IntegerRange(0, 2), new IntegerRange(0, 4),
                new IntegerRange(1, 120), false, 12000,
                Map.of("LEGACY_STABLE", 1, "SINGLE_SKILLED", 0,
                        "TEAM_READONLY", 8, "TEAM_AUTONOMOUS_READONLY", 2),
                List.of(RuntimeProfile.LEGACY_STABLE, RuntimeProfile.SINGLE_SKILLED), reserved,
                profileAdmission.evidenceStatus(), "1");
    }

    private void validateEnablement(long employeeId) {
        EmployeeSummary employee = store.findEmployee(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Digital employee not found"));
        long currentVersionId = employee.currentPublishedVersionId() == null
                ? 0 : employee.currentPublishedVersionId();
        if (currentVersionId <= 0) throw new IllegalStateException("EMPLOYEE_NOT_PUBLISHED");
        EmployeeVersionDetail version = store.findVersion(employeeId, currentVersionId)
                .orElseThrow(() -> new IllegalStateException("EMPLOYEE_NOT_PUBLISHED"));
        RuntimeProfileAdmissionPolicy.Admission admission = profileAdmission.evaluate(version.summary().profile());
        if (!admission.enabled()) throw new IllegalStateException(
                admission.reasonCode() == null ? "PROFILE_DISABLED" : admission.reasonCode());
        if (!version.runtimeBundleAvailable()) throw new IllegalStateException("RUNTIME_BUNDLE_MISSING");
        for (var capability : version.capabilities()) {
            if (!capability.enabled()) throw new IllegalStateException("DEPENDENCY_UNAVAILABLE");
        }
        if (version.members().stream().anyMatch(member -> !member.employeeEnabled()
                || member.profile() != RuntimeProfile.LEGACY_STABLE && member.profile() != RuntimeProfile.SINGLE_SKILLED))
            throw new IllegalStateException("DEPENDENCY_UNAVAILABLE");
    }

    private static int memberLimit(RuntimeProfile profile) {
        return switch (profile) {
            case TEAM_READONLY -> 8;
            case TEAM_AUTONOMOUS_READONLY -> 2;
            default -> 0;
        };
    }

    private static int normalizeLimit(Integer value) {
        int limit = value == null ? DEFAULT_LIMIT : value;
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 100");
        return limit;
    }

    private static String required(String value, int max, String field) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > max)
            throw new IllegalArgumentException(field + " must be between 1 and " + max + " characters");
        return value.trim();
    }

    private static void requireRequest(String requestId, String reason, long actorId) {
        if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128)
            throw new IllegalArgumentException("requestId must be between 1 and 128 characters");
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("reason must be between 1 and 500 characters");
    }

    public record RuntimeOptions(List<ProfileOption> profiles, IntegerRange maxIterations,
                                 IntegerRange maxParallelDelegations, IntegerRange maxExpertInvocationsPerRun,
                                 IntegerRange syncTimeoutSeconds, boolean memoryEnabled,
                                 int instructionMaxLength, Map<String, Integer> memberLimits,
                                 List<RuntimeProfile> supportedMemberProfiles,
                                 List<ReservedDelegationTarget> reservedDelegationTargets,
                                 String admissionEvidenceStatus, String optionsVersion) {
        public RuntimeOptions {
            profiles = List.copyOf(profiles);
            memberLimits = Map.copyOf(memberLimits);
            supportedMemberProfiles = List.copyOf(supportedMemberProfiles);
            reservedDelegationTargets = List.copyOf(reservedDelegationTargets);
        }
    }

    public record ProfileOption(RuntimeProfile profile, int schemaVersion, boolean configuredEnabled,
                                boolean publishEnabled, String disabledReasonCode, int memberLimit) {}
    public record IntegerRange(int min, int max) {}
    public record ReservedDelegationTarget(String kind, String targetRoleId, boolean readonly,
                                           List<RuntimeProfile> supportedProfiles, String profileGate) {
        public ReservedDelegationTarget { supportedProfiles = List.copyOf(supportedProfiles); }
    }
}
