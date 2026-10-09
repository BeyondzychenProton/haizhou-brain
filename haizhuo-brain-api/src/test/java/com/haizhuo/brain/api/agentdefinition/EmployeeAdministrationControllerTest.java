package com.haizhuo.brain.api.agentdefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.AgentDefinitionDraft;
import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.EmployeeAdminCommandExecutor;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationService;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeConfiguration;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeProfileSettings;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionPublisher;
import com.haizhuo.brain.platform.tool.CapabilityExecutorRegistry;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EmployeeAdministrationControllerTest {
    @Test
    void createAndStatusWritesUseAuthenticatedAdministratorAndRequestKeys() {
        TestStore store = new TestStore();
        var controller = new EmployeeAdministrationController(service(store));
        var actor = new AuthenticatedUser(new UserId(71), Set.of(PlatformRole.PLATFORM_ADMIN), 3, false);

        var created = controller.create(actor, new EmployeeAdministrationController.CreateRequest(
                "new-employee", "新员工", "create-1", "初始化员工")).block();
        assertEquals(71, store.lastActorId);
        assertEquals("create-1", store.lastRequestId);
        assertEquals(8, created.employee().employeeId());
        assertFalse(created.employee().enabled());
        assertFalse(created.employee().published());

        var disabled = controller.setStatus(actor, 8,
                new EmployeeAdministrationController.StatusRequest(0, false, "disable-1", "暂停新会话")).block();
        assertEquals(71, store.lastActorId);
        assertEquals("disable-1", store.lastRequestId);
        assertEquals(1, disabled.rowVersion());
    }

    @Test
    void exactVersionPathKeepsEmployeeOwnershipAndMissingVersionIs404() {
        TestStore store = new TestStore();
        var controller = new EmployeeAdministrationController(service(store));

        var detail = controller.version(7, 70).block();
        assertEquals(70, detail.summary().versionId());
        assertEquals("管理员正文", detail.instructions());
        assertEquals(7, store.lastVersionEmployeeId);
        assertEquals(70, store.lastVersionId);

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> controller.version(7, 71).block());
        assertEquals(404, missing.getStatusCode().value());
    }

    private static EmployeeAdministrationService service(TestStore store) {
        AgentDefinitionRepository definitionsRepository = (AgentDefinitionRepository) Proxy.newProxyInstance(
                AgentDefinitionRepository.class.getClassLoader(), new Class<?>[]{AgentDefinitionRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) return "UnusedDefinitionsRepository";
                    if (Optional.class.isAssignableFrom(method.getReturnType())) return Optional.empty();
                    if (List.class.isAssignableFrom(method.getReturnType())) return List.of();
                    return null;
                });
        CapabilityExecutorRegistry executors = new CapabilityExecutorRegistry() {
            @Override public boolean supports(String implementationKey, String capabilityCode) { return false; }
            @Override public com.haizhuo.brain.platform.tool.CapabilityExecutor resolve(
                    com.haizhuo.brain.platform.employee.CapabilityCatalogEntry entry) { return null; }
        };
        HarnessDefinitionPublisher publisher = (employeeId, expectedRevision, audit) -> {
            throw new UnsupportedOperationException();
        };
        var settings = new EmployeeRuntimeProfileSettings(Set.of(RuntimeProfile.LEGACY_STABLE));
        var management = new AgentDefinitionManagementService(definitionsRepository, executors, publisher, null,
                settings.enabledProfiles());
        EmployeeAdminCommandExecutor commands = new EmployeeAdminCommandExecutor() {
            @Override public <T> T execute(Command command, Class<T> responseType, java.util.function.Supplier<T> action) {
                return action.get();
            }
        };
        return new EmployeeAdministrationService(store, commands, management, settings);
    }

    private static final class TestStore implements EmployeeAdministrationStore {
        private final Instant now = Instant.parse("2026-10-09T00:00:00Z");
        private long lastActorId;
        private String lastRequestId;
        private long lastVersionEmployeeId;
        private long lastVersionId;
        private EmployeeSummary createdSummary;

        @Override public Optional<EmployeeSummary> findEmployee(long employeeId) {
            return Optional.ofNullable(createdSummary).filter(item -> item.employeeId() == employeeId);
        }

        @Override public EmployeePage listEmployees(String query, Boolean enabled, Boolean published, String cursor, int limit) {
            return new EmployeePage(List.of(), null, false);
        }

        @Override public CreatedEmployee createEmployee(String employeeCode, String displayName, long actorId,
                                                        String requestId, String requestHash, String reason) {
            lastActorId = actorId;
            lastRequestId = requestId;
            createdSummary = new EmployeeSummary(8, employeeCode, displayName, false, false, null, 0, now);
            var draft = new AgentDefinitionDraft(8, 1, "补充指令", "openai", "test-model",
                    List.of(), EmployeeRuntimeConfiguration.legacyStable(), now);
            return new CreatedEmployee(createdSummary, draft);
        }

        @Override public EmployeeSummary updateEmployee(long employeeId, long expectedRowVersion, String displayName,
                                                        long actorId, String requestId, String requestHash, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override public EmployeeSummary setEmployeeEnabled(long employeeId, long expectedRowVersion, boolean enabled,
                                                            long actorId, String requestId, String requestHash, String reason) {
            lastActorId = actorId;
            lastRequestId = requestId;
            createdSummary = new EmployeeSummary(employeeId, "new-employee", "新员工", enabled, false, null,
                    expectedRowVersion + 1, now);
            return createdSummary;
        }

        @Override public EmployeeVersionPage listVersions(long employeeId, String cursor, int limit) {
            return new EmployeeVersionPage(List.of(), null, false);
        }

        @Override public Optional<EmployeeVersionDetail> findVersion(long employeeId, long versionId) {
            lastVersionEmployeeId = employeeId;
            lastVersionId = versionId;
            if (employeeId != 7 || versionId != 70) return Optional.empty();
            var summary = new EmployeeVersionSummary(70, 7, 2, "a".repeat(64), RuntimeProfile.LEGACY_STABLE,
                    42, now, true);
            return Optional.of(new EmployeeVersionDetail(summary, "管理员正文", "openai", "model",
                    EmployeeRuntimeConfiguration.legacyStable(), List.of(), List.of(), true));
        }
    }
}
