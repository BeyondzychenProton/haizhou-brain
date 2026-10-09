package com.haizhuo.brain.platform.employee;

import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionPublisher;
import com.haizhuo.brain.platform.tool.CapabilityExecutorRegistry;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentDefinitionManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void disabledRuntimeProfileIsNotPublishableAndHasDeterministicPreviewHash() {
        AgentDefinitionDraft draft = draft(EmployeeRuntimeConfiguration.singleSkilled(6), List.of());
        AgentDefinitionRepository repository = repository(draft, Map.of(), Map.of(), Map.of());
        AgentDefinitionManagementService service = service(repository, Set.of(RuntimeProfile.LEGACY_STABLE));

        var first = service.validateDraft(draft.employeeId());
        var second = service.validateDraft(draft.employeeId());

        assertFalse(first.publishable());
        assertTrue(first.issues().stream().anyMatch(issue -> issue.code().equals("PROFILE_DISABLED")
                && issue.fieldPath().equals("configuration.profile")));
        assertTrue(first.previewHash().matches("[a-f0-9]{64}"));
        assertEquals(first.previewHash(), second.previewHash());
    }

    @Test
    void autonomousTeamRequiresFrozenMembersWhenProfileIsExplicitlyEnabled() {
        EmployeeRuntimeConfiguration configuration = new EmployeeRuntimeConfiguration(2,
                RuntimeProfile.TEAM_AUTONOMOUS_READONLY,
                new EmployeeRuntimeConfiguration.RuntimePolicy(5, 1, 2, 60, false),
                new EmployeeRuntimeConfiguration.TeamConfiguration("analyst", List.of("analyst"), List.of()),
                List.of());
        AgentDefinitionDraft draft = draft(configuration, List.of());
        AgentDefinitionRepository repository = repository(draft, Map.of(), Map.of(), Map.of());
        AgentDefinitionManagementService service = service(repository,
                Set.of(RuntimeProfile.LEGACY_STABLE, RuntimeProfile.TEAM_AUTONOMOUS_READONLY));

        var result = service.validateDraft(draft.employeeId());

        assertFalse(result.publishable());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("TEAM_MEMBERS_REQUIRED")
                && issue.fieldPath().equals("configuration.members")));
        assertFalse(result.issues().stream().anyMatch(issue -> issue.code().equals("PROFILE_NOT_IMPLEMENTED")));
    }

    @Test
    void legacyDraftUpdateWithoutConfigurationPreservesExistingProfile() {
        EmployeeRuntimeConfiguration configuration = EmployeeRuntimeConfiguration.singleSkilled(7);
        AgentDefinitionDraft draft = draft(configuration, List.of());
        AgentDefinitionRepository repository = repository(draft, Map.of(), Map.of(), Map.of());
        AgentDefinitionManagementService service = service(repository, Set.of(RuntimeProfile.LEGACY_STABLE));

        var saved = service.saveDraft(draft.employeeId(), draft.draftRevision(),
                new AgentDefinitionManagementService.DraftUpdate("指令更新", "openai-compatible", "test-model", List.of()),
                71, "更新指令");

        assertEquals(configuration, saved.configuration());
    }

    @Test
    void teamMemberFromAnotherTenantGetsFieldSpecificValidationError() {
        int ownerId = 51;
        int memberId = 61;
        EmployeeRuntimeConfiguration configuration = new EmployeeRuntimeConfiguration(2, RuntimeProfile.TEAM_READONLY,
                new EmployeeRuntimeConfiguration.RuntimePolicy(5, 1, 2, 60, false),
                new EmployeeRuntimeConfiguration.TeamConfiguration("analyst", List.of("analyst"), List.of()),
                List.of(new EmployeeRuntimeConfiguration.FixedMember("analyst", memberId, 901, 4)));
        AgentDefinitionDraft draft = draft(ownerId, configuration, List.of());
        Map<Long, DigitalEmployee> employees = Map.of(
                (long) ownerId, employee(ownerId, 1),
                (long) memberId, employee(memberId, 2));
        AgentDefinitionRepository repository = repository(draft, employees, Map.of(), Map.of());

        var result = service(repository, Set.of(RuntimeProfile.LEGACY_STABLE)).validateDraft(ownerId);

        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("MEMBER_TENANT_MISMATCH")
                && issue.fieldPath().equals("configuration.members[0].employeeId")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("PROFILE_DISABLED")));
    }

    @Test
    void exactSameTenantMemberVersionCanBeValidatedWithoutResolvingLatest() {
        int ownerId = 52;
        int memberId = 62;
        int versionId = 902;
        EmployeeRuntimeConfiguration configuration = new EmployeeRuntimeConfiguration(2, RuntimeProfile.TEAM_READONLY,
                new EmployeeRuntimeConfiguration.RuntimePolicy(5, 1, 2, 60, false),
                new EmployeeRuntimeConfiguration.TeamConfiguration("analyst", List.of("analyst"), List.of()),
                List.of(new EmployeeRuntimeConfiguration.FixedMember("analyst", memberId, versionId, 4)));
        AgentDefinitionDraft draft = draft(ownerId, configuration, List.of());
        DigitalEmployee member = employee(memberId, 1);
        PublishedEmployee exact = new PublishedEmployee(member,
                new AgentDefinitionVersion(versionId, memberId, 3, "冻结版本", "openai", "test-model", NOW), List.of());
        AgentDefinitionRepository repository = repository(draft,
                Map.of((long) ownerId, employee(ownerId, 1), (long) memberId, member),
                Map.of(new VersionKey(memberId, versionId), exact), Map.of());

        var result = service(repository, Set.of(RuntimeProfile.LEGACY_STABLE)).validateDraft(ownerId);

        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("PROFILE_DISABLED")));
        assertFalse(result.issues().stream().anyMatch(issue -> issue.code().startsWith("MEMBER_")));
    }

    @Test
    void readonlyTeamAllowsCoordinatorToDelegateToReservedNativeTargets() {
        int ownerId = 53;
        int memberId = 63;
        int versionId = 903;
        EmployeeRuntimeConfiguration configuration = new EmployeeRuntimeConfiguration(2, RuntimeProfile.TEAM_READONLY,
                new EmployeeRuntimeConfiguration.RuntimePolicy(5, 1, 2, 60, false),
                new EmployeeRuntimeConfiguration.TeamConfiguration("analyst", List.of("analyst"), List.of(
                        new EmployeeRuntimeConfiguration.RoleDelegation("coordinator", "general-purpose"),
                        new EmployeeRuntimeConfiguration.RoleDelegation("coordinator", "dynamic-expert"))),
                List.of(new EmployeeRuntimeConfiguration.FixedMember("analyst", memberId, versionId, 8)));
        AgentDefinitionDraft draft = draft(ownerId, configuration, List.of());
        DigitalEmployee member = employee(memberId, 1);
        PublishedEmployee exact = new PublishedEmployee(member,
                new AgentDefinitionVersion(versionId, memberId, 1, "冻结只读成员", "openai", "test-model", NOW), List.of());
        AgentDefinitionRepository repository = repository(draft,
                Map.of((long) ownerId, employee(ownerId, 1), (long) memberId, member),
                Map.of(new VersionKey(memberId, versionId), exact), Map.of());

        var result = service(repository, Set.of(RuntimeProfile.LEGACY_STABLE, RuntimeProfile.TEAM_READONLY))
                .validateDraft(ownerId);

        assertTrue(result.publishable(), () -> result.issues().toString());
    }

    @Test
    void configuredProfileWithoutDeploymentEvidenceCannotBePublished() {
        AgentDefinitionDraft draft = draft(EmployeeRuntimeConfiguration.singleSkilled(6), List.of());
        AgentDefinitionRepository repository = repository(draft, Map.of(), Map.of(), Map.of());
        CapabilityExecutorRegistry executors = new CapabilityExecutorRegistry() {
            @Override public boolean supports(String implementationKey, String capabilityCode) { return true; }
            @Override public com.haizhuo.brain.platform.tool.CapabilityExecutor resolve(CapabilityCatalogEntry capability) { return null; }
        };
        HarnessDefinitionPublisher publisher = (employeeId, expectedRevision, audit) -> null;
        var service = new AgentDefinitionManagementService(repository, executors, publisher, null,
                RuntimeProfileAdmissionPolicy.configuredOnly(Set.of(RuntimeProfile.LEGACY_STABLE,
                        RuntimeProfile.SINGLE_SKILLED)));

        var result = service.validateDraft(draft.employeeId());

        assertFalse(result.publishable());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("PROFILE_VERIFICATION_MISSING")
                && issue.fieldPath().equals("configuration.profile")));
    }

    @Test
    void reservedTargetsRejectUnsupportedSourcesProfilesAndFixedMemberRoleAliases() {
        int ownerId = 54;
        int memberId = 64;
        int versionId = 904;
        EmployeeRuntimeConfiguration configuration = new EmployeeRuntimeConfiguration(2, RuntimeProfile.TEAM_READONLY,
                new EmployeeRuntimeConfiguration.RuntimePolicy(5, 1, 2, 60, false),
                new EmployeeRuntimeConfiguration.TeamConfiguration("analyst", List.of("analyst"), List.of(
                        new EmployeeRuntimeConfiguration.RoleDelegation("analyst", "general-purpose"),
                        new EmployeeRuntimeConfiguration.RoleDelegation("coordinator", "future-target"))),
                List.of(new EmployeeRuntimeConfiguration.FixedMember("analyst", memberId, versionId, 4),
                        new EmployeeRuntimeConfiguration.FixedMember("dynamic-expert", memberId, versionId, 4)));
        AgentDefinitionDraft draft = draft(ownerId, configuration, List.of());
        DigitalEmployee member = employee(memberId, 1);
        PublishedEmployee exact = new PublishedEmployee(member,
                new AgentDefinitionVersion(versionId, memberId, 1, "冻结只读成员", "openai", "test-model", NOW), List.of());
        AgentDefinitionRepository repository = repository(draft,
                Map.of((long) ownerId, employee(ownerId, 1), (long) memberId, member),
                Map.of(new VersionKey(memberId, versionId), exact), Map.of());

        var result = service(repository, Set.of(RuntimeProfile.LEGACY_STABLE, RuntimeProfile.TEAM_READONLY))
                .validateDraft(ownerId);

        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("DELEGATION_SOURCE_NOT_SUPPORTED")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("DELEGATION_TARGET_UNKNOWN")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("ROLE_ID_RESERVED")));
    }

    private static AgentDefinitionManagementService service(AgentDefinitionRepository repository,
                                                            Set<RuntimeProfile> enabledProfiles) {
        CapabilityExecutorRegistry executors = new CapabilityExecutorRegistry() {
            @Override public boolean supports(String implementationKey, String capabilityCode) { return true; }
            @Override public com.haizhuo.brain.platform.tool.CapabilityExecutor resolve(CapabilityCatalogEntry capability) { return null; }
        };
        HarnessDefinitionPublisher publisher = (employeeId, expectedRevision, audit) -> null;
        RuntimeProfileAdmissionPolicy testVerifiedProfiles = profile -> {
            boolean enabled = enabledProfiles.contains(profile);
            return new RuntimeProfileAdmissionPolicy.Admission(enabled, enabled, enabled,
                    enabled ? null : "PROFILE_DISABLED", enabled ? "TEST_VERIFICATION" : "NOT_CONFIGURED");
        };
        return new AgentDefinitionManagementService(repository, executors, publisher, null, testVerifiedProfiles);
    }

    private static AgentDefinitionRepository repository(AgentDefinitionDraft draft,
                                                        Map<Long, DigitalEmployee> employees,
                                                        Map<VersionKey, PublishedEmployee> versions,
                                                        Map<String, CapabilityCatalogEntry> capabilities) {
        return (AgentDefinitionRepository) Proxy.newProxyInstance(AgentDefinitionRepository.class.getClassLoader(),
                new Class<?>[]{AgentDefinitionRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findDraft" -> Optional.of(draft);
                    case "findEmployee" -> Optional.ofNullable(employees.get((Long) args[0]));
                    case "findPublishedVersion" -> Optional.ofNullable(versions.get(new VersionKey((Long) args[1], (Long) args[2])));
                    case "findPublished" -> Optional.empty();
                    case "findCapability" -> Optional.ofNullable(capabilities.get(args[0] + "@" + args[1]));
                    case "listCapabilities", "listUserCapabilityGrants" -> List.of();
                    case "isCapabilityEnabled", "hasUserCapabilityGrant" -> true;
                    case "saveDraft" -> new AgentDefinitionDraft(draft.employeeId(), draft.draftRevision() + 1,
                            (String) args[2], (String) args[3], (String) args[4], (List<CapabilitySelection>) args[5],
                            (EmployeeRuntimeConfiguration) args[6], NOW);
                    default -> null;
                });
    }

    private static AgentDefinitionDraft draft(EmployeeRuntimeConfiguration configuration, List<CapabilitySelection> capabilities) {
        return draft(50, configuration, capabilities);
    }

    private static AgentDefinitionDraft draft(long employeeId, EmployeeRuntimeConfiguration configuration,
                                              List<CapabilitySelection> capabilities) {
        return new AgentDefinitionDraft(employeeId, 4, "原始指令", "openai-compatible", "test-model",
                capabilities, configuration, NOW);
    }

    private static DigitalEmployee employee(long id, long tenantId) {
        return new DigitalEmployee(id, new TenantId(tenantId), "employee-" + id, "员工" + id, true);
    }

    private record VersionKey(long employeeId, long versionId) {}
}
