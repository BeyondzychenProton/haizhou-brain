package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.kernel.identity.TenantId;
import java.util.List;
import java.util.Optional;

/** 员工定义、能力目录、授权种子和 Run 能力快照的持久化边界。 */
public interface AgentDefinitionRepository extends EmployeeCatalog {
    Optional<AgentDefinitionDraft> findDraft(long employeeId);
    List<CapabilityCatalogEntry> listCapabilities();
    Optional<CapabilityCatalogEntry> findCapability(String capabilityCode, String revision);
    Optional<CapabilityCatalogEntry> findCapabilityByRevisionId(long capabilityRevisionId);

    AgentDefinitionDraft saveDraft(long employeeId, int expectedDraftRevision, String instructions,
                                   String modelProvider, String modelName,
                                   List<CapabilitySelection> capabilities, AgentDefinitionManagementAudit audit);

    PublishedEmployee publish(long employeeId, int expectedDraftRevision, AgentDefinitionManagementAudit audit);
    void setCapabilityEnabled(String capabilityCode, boolean enabled, AgentDefinitionManagementAudit audit);
    boolean isCapabilityEnabled(String capabilityCode, String revision);
    boolean hasUserCapabilityGrant(long userId, String capabilityCode);
    void setUserCapabilityGrant(long userId, String capabilityCode, boolean enabled, AgentDefinitionManagementAudit audit);
    void recordToolInvocation(ToolInvocationAudit audit);

    default Optional<PublishedEmployee> findPublished(long employeeId) {
        return findPublished(new TenantId(1), employeeId);
    }
}
