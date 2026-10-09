package com.haizhuo.brain.platform.employee;

import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeeSummary;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeeVersionDetail;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeeVersionSummary;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionPublisher;
import com.haizhuo.brain.platform.tool.CapabilityExecutorRegistry;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EmployeeAdministrationServiceTest {
    @Test
    void runtimeOptionsReflectOneProfileGateAndKeepReservedTargetsReadOnly() {
        var service = service(new TestStore(), new EmployeeRuntimeProfileSettings(Set.of(RuntimeProfile.LEGACY_STABLE)),
                inertDefinitions(new AtomicReference<>()));

        var options = service.runtimeOptions();

        assertFalse(options.memoryEnabled());
        assertEquals(12000, options.instructionMaxLength());
        assertEquals(new EmployeeAdministrationService.IntegerRange(1, 20), options.maxIterations());
        var legacy = options.profiles().stream().filter(item -> item.profile() == RuntimeProfile.LEGACY_STABLE).findFirst().orElseThrow();
        var single = options.profiles().stream().filter(item -> item.profile() == RuntimeProfile.SINGLE_SKILLED).findFirst().orElseThrow();
        assertTrue(legacy.configuredEnabled());
        assertTrue(legacy.publishEnabled());
        assertFalse(single.configuredEnabled());
        assertFalse(single.publishEnabled());
        assertEquals("PROFILE_DISABLED", single.disabledReasonCode());
        assertEquals(List.of("general-purpose", "dynamic-expert"), options.reservedDelegationTargets().stream()
                .map(EmployeeAdministrationService.ReservedDelegationTarget::targetRoleId).toList());
        assertTrue(options.reservedDelegationTargets().stream().allMatch(target -> target.readonly()
                && target.supportedProfiles().equals(List.of(RuntimeProfile.TEAM_READONLY))));
        assertEquals("NOT_EVALUATED", options.admissionEvidenceStatus());
    }

    @Test
    void restoringImmutableVersionUsesSameRequestIdForReceiptAndManagementAudit() {
        TestStore store = new TestStore();
        AtomicReference<AgentDefinitionManagementAudit> savedAudit = new AtomicReference<>();
        AgentDefinitionManagementService definitions = inertDefinitions(savedAudit);
        AtomicReference<EmployeeAdminCommandExecutor.Command> receivedCommand = new AtomicReference<>();
        EmployeeAdminCommandExecutor commands = new EmployeeAdminCommandExecutor() {
            @Override public <T> T execute(Command command, Class<T> responseType, java.util.function.Supplier<T> action) {
                receivedCommand.set(command);
                return action.get();
            }
        };
        var service = new EmployeeAdministrationService(store, commands, definitions,
                new EmployeeRuntimeProfileSettings(Set.of(RuntimeProfile.LEGACY_STABLE, RuntimeProfile.SINGLE_SKILLED)));

        AgentDefinitionDraft restored = service.restoreDraft(5, 55, 3, "restore-req-1", 42, "恢复历史配置");

        assertEquals(4, restored.draftRevision());
        assertEquals("历史指令", restored.instructions());
        assertEquals(EmployeeRuntimeConfiguration.singleSkilled(7), restored.configuration());
        assertEquals("restore-req-1", savedAudit.get().requestId());
        assertEquals("RESTORE_DRAFT", receivedCommand.get().action());
        assertEquals("restore-req-1", receivedCommand.get().requestId());
        assertEquals(5L, receivedCommand.get().employeeId());
        assertTrue(receivedCommand.get().requestHash().matches("[a-f0-9]{64}"));
    }

    @Test
    void enablementChecksPublishedProfileBundleAndDependenciesBeforeStatusWrite() {
        TestStore store = new TestStore();
        store.employee = new EmployeeSummary(5, "employee-5", "员工", false, true, 55L, 2, Instant.EPOCH);
        store.version = detail(55, RuntimeProfile.SINGLE_SKILLED, false, List.of());
        var legacyOnly = new EmployeeAdministrationService(store, directCommands(), inertDefinitions(new AtomicReference<>()),
                new EmployeeRuntimeProfileSettings(Set.of(RuntimeProfile.LEGACY_STABLE)));
        assertEquals("PROFILE_DISABLED", assertThrows(IllegalStateException.class,
                () -> legacyOnly.setEmployeeEnabled(5, 2, true, "enable-1", 42, "启用员工")).getMessage());
        assertFalse(store.statusWriteCalled);

        var enabledProfiles = new EmployeeAdministrationService(store, directCommands(), inertDefinitions(new AtomicReference<>()),
                new EmployeeRuntimeProfileSettings(Set.of(RuntimeProfile.LEGACY_STABLE, RuntimeProfile.SINGLE_SKILLED)),
                testVerifiedProfiles());
        assertEquals("RUNTIME_BUNDLE_MISSING", assertThrows(IllegalStateException.class,
                () -> enabledProfiles.setEmployeeEnabled(5, 2, true, "enable-2", 42, "启用员工")).getMessage());
        store.version = detail(55, RuntimeProfile.LEGACY_STABLE, true,
                List.of(new EmployeeAdministrationStore.CapabilityDetail("tool.read", "1", "只读工具",
                        "TOOL", "a".repeat(64), false)));
        assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> enabledProfiles.setEmployeeEnabled(5, 2, true, "enable-3", 42, "启用员工")).getMessage());
        assertFalse(store.statusWriteCalled);
    }

    private static EmployeeAdministrationService service(TestStore store, EmployeeRuntimeProfileSettings settings,
                                                         AgentDefinitionManagementService definitions) {
        return new EmployeeAdministrationService(store, directCommands(), definitions, settings);
    }

    private static RuntimeProfileAdmissionPolicy testVerifiedProfiles() {
        return profile -> new RuntimeProfileAdmissionPolicy.Admission(true, true, true, null, "TEST_VERIFICATION");
    }

    private static EmployeeAdminCommandExecutor directCommands() {
        return new EmployeeAdminCommandExecutor() {
            @Override public <T> T execute(Command command, Class<T> responseType, java.util.function.Supplier<T> action) {
                return action.get();
            }
        };
    }

    private static AgentDefinitionManagementService inertDefinitions(AtomicReference<AgentDefinitionManagementAudit> audit) {
        AgentDefinitionDraft draft = new AgentDefinitionDraft(5, 3, "当前草稿", "openai", "test-model", List.of(),
                EmployeeRuntimeConfiguration.singleSkilled(6), Instant.EPOCH);
        CapabilityCatalogEntry capability = new CapabilityCatalogEntry(1, "tool.read",
                CapabilityBinding.CapabilityType.TOOL, "1", "只读工具", "测试", "tool_read", "test",
                "tool.read", java.util.Map.of("type", "object"), true, false);
        AgentDefinitionRepository repository = (AgentDefinitionRepository) Proxy.newProxyInstance(
                AgentDefinitionRepository.class.getClassLoader(), new Class<?>[]{AgentDefinitionRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findDraft" -> Optional.of(draft);
                    case "findCapability" -> Optional.of(capability);
                    case "saveDraft" -> {
                        Object[] values = args;
                        if (audit != null) audit.set((AgentDefinitionManagementAudit) values[values.length - 1]);
                        yield new AgentDefinitionDraft((Long) values[0], (Integer) values[1] + 1,
                                (String) values[2], (String) values[3], (String) values[4],
                                (List<CapabilitySelection>) values[5], (EmployeeRuntimeConfiguration) values[6], Instant.EPOCH);
                    }
                    case "listCapabilities", "listUserCapabilityGrants" -> List.of();
                    case "findEmployee", "findPublished", "findPublishedVersion", "findCapabilityByRevisionId" -> Optional.empty();
                    case "isCapabilityEnabled", "hasUserCapabilityGrant" -> false;
                    case "toString" -> "InertAgentDefinitionRepository";
                    default -> null;
                });
        CapabilityExecutorRegistry executors = new CapabilityExecutorRegistry() {
            @Override public boolean supports(String implementationKey, String capabilityCode) { return false; }
            @Override public com.haizhuo.brain.platform.tool.CapabilityExecutor resolve(CapabilityCatalogEntry entry) { return null; }
        };
        HarnessDefinitionPublisher publisher = (employeeId, expectedRevision, change) -> { throw new UnsupportedOperationException(); };
        return new AgentDefinitionManagementService(repository, executors, publisher, null,
                Set.of(RuntimeProfile.LEGACY_STABLE, RuntimeProfile.SINGLE_SKILLED));
    }

    private static EmployeeVersionDetail detail(long versionId, RuntimeProfile profile, boolean bundle,
                                                List<EmployeeAdministrationStore.CapabilityDetail> capabilities) {
        return new EmployeeVersionDetail(new EmployeeVersionSummary(versionId, 5, 1, "a".repeat(64), profile,
                42, Instant.EPOCH, true), "历史指令", "openai", "test-model",
                profile == RuntimeProfile.LEGACY_STABLE ? EmployeeRuntimeConfiguration.legacyStable()
                        : EmployeeRuntimeConfiguration.singleSkilled(7), capabilities, List.of(), bundle);
    }

    private static class TestStore implements EmployeeAdministrationStore {
        EmployeeSummary employee = new EmployeeSummary(5, "employee-5", "员工", true, false, null, 0, Instant.EPOCH);
        EmployeeVersionDetail version = detail(55, RuntimeProfile.SINGLE_SKILLED, true, List.of());
        boolean statusWriteCalled;

        @Override public Optional<EmployeeSummary> findEmployee(long employeeId) { return Optional.of(employee); }
        @Override public EmployeePage listEmployees(String query, Boolean enabled, Boolean published, String cursor, int limit) {
            return new EmployeePage(List.of(), null, false);
        }
        @Override public CreatedEmployee createEmployee(String employeeCode, String displayName, long actorId,
                                                        String requestId, String requestHash, String reason) { throw new UnsupportedOperationException(); }
        @Override public EmployeeSummary updateEmployee(long employeeId, long expectedRowVersion, String displayName,
                                                        long actorId, String requestId, String requestHash, String reason) {
            throw new UnsupportedOperationException();
        }
        @Override public EmployeeSummary setEmployeeEnabled(long employeeId, long expectedRowVersion, boolean enabled,
                                                            long actorId, String requestId, String requestHash, String reason) {
            statusWriteCalled = true;
            return employee;
        }
        @Override public EmployeeVersionPage listVersions(long employeeId, String cursor, int limit) {
            return new EmployeeVersionPage(List.of(), null, false);
        }
        @Override public Optional<EmployeeVersionDetail> findVersion(long employeeId, long versionId) {
            return version.summary().versionId() == versionId ? Optional.of(version) : Optional.empty();
        }
    }
}
