package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.runtime.api.RuntimeCapabilityProviderCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
public class AgentDefinitionManagementService {
    private final AgentDefinitionRepository repository;
    private final RuntimeCapabilityProviderCatalog providers;

    public AgentDefinitionManagementService(AgentDefinitionRepository repository,
                                            RuntimeCapabilityProviderCatalog providers) {
        this.repository = repository;
        this.providers = providers;
    }

    public List<CapabilityCatalogEntry> listCapabilities() { return repository.listCapabilities(); }

    public AgentDefinitionDraft getDraft(long employeeId) {
        return repository.findDraft(employeeId).orElseThrow(() -> new IllegalArgumentException("Agent draft not found"));
    }

    public AgentDefinitionDraft saveDraft(long employeeId, int expectedRevision, DraftUpdate update, long actorId, String reason) {
        if (update.instructions() == null || update.instructions().isBlank() || update.instructions().length() > 12000)
            throw new IllegalArgumentException("instructions must be between 1 and 12000 characters");
        if (!Set.of("openai", "openai-compatible", "dashscope").contains(update.modelProvider()))
            throw new IllegalArgumentException("Unsupported model provider");
        if (update.modelName() == null || update.modelName().isBlank() || update.modelName().length() > 128)
            throw new IllegalArgumentException("modelName is required and must be at most 128 characters");
        if (update.capabilities() == null || update.capabilities().isEmpty())
            throw new IllegalArgumentException("At least one capability must be selected");
        Set<String> unique = new HashSet<>();
        for (CapabilitySelection selection : update.capabilities()) {
            if (selection.capabilityCode() == null || selection.revision() == null
                    || !unique.add(selection.capabilityCode()))
                throw new IllegalArgumentException("Capability selections must be complete and unique");
            CapabilityCatalogEntry entry = repository.findCapability(selection.capabilityCode(), selection.revision())
                    .orElseThrow(() -> new IllegalArgumentException("Capability revision does not exist"));
            if (entry.type() != CapabilityBinding.CapabilityType.TOOL)
                throw new IllegalArgumentException("Only registered tools are supported in this release");
        }
        return repository.saveDraft(employeeId, expectedRevision, update.instructions().trim(),
                update.modelProvider(), update.modelName().trim(), update.capabilities(),
                AgentDefinitionManagementAudit.pending(actorId, "DRAFT_SAVED", "DIGITAL_EMPLOYEE",
                        String.valueOf(employeeId), null, reason));
    }

    public ValidationResult validateDraft(long employeeId) {
        AgentDefinitionDraft draft = getDraft(employeeId);
        List<ValidationIssue> issues = new ArrayList<>();
        if (draft.capabilities().isEmpty()) issues.add(new ValidationIssue("NO_CAPABILITIES", "至少选择一项能力"));
        for (CapabilitySelection selection : draft.capabilities()) {
            CapabilityCatalogEntry entry = repository.findCapability(selection.capabilityCode(), selection.revision()).orElse(null);
            if (entry == null) {
                issues.add(new ValidationIssue("REVISION_MISSING", selection.capabilityCode() + " 的修订不存在"));
                continue;
            }
            if (!entry.enabled() || !repository.isCapabilityEnabled(entry.capabilityCode(), entry.revision()))
                issues.add(new ValidationIssue("CAPABILITY_DISABLED", selection.capabilityCode() + " 当前已停用"));
            if (!providers.supports(entry.implementationKey(), entry.capabilityCode()))
                issues.add(new ValidationIssue("PROVIDER_UNAVAILABLE", selection.capabilityCode() + " 没有可用的运行时提供者"));
            if (entry.toolName() == null || entry.toolName().isBlank() || entry.inputSchema().isEmpty())
                issues.add(new ValidationIssue("TOOL_SCHEMA_INVALID", selection.capabilityCode() + " 缺少工具名称或参数 Schema"));
        }
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues), draft.draftRevision());
    }

    public PublishedEmployee publish(long employeeId, int expectedRevision, String requestId, long actorId, String reason) {
        ValidationResult validation = validateDraft(employeeId);
        if (!validation.publishable()) throw new DefinitionNotPublishableException(validation);
        return repository.publish(employeeId, expectedRevision,
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
        if (repository.listCapabilities().stream().noneMatch(entry -> entry.capabilityCode().equals(capabilityCode)))
            throw new IllegalArgumentException("Capability does not exist");
        repository.setUserCapabilityGrant(userId, capabilityCode, enabled,
                AgentDefinitionManagementAudit.pending(actorId, "USER_CAPABILITY_GRANT_CHANGED", "PLATFORM_USER",
                        String.valueOf(userId), null, reason));
    }

    public record DraftUpdate(String instructions, String modelProvider, String modelName,
                              List<CapabilitySelection> capabilities) {}
    public record ValidationIssue(String code, String message) {}
    public record ValidationResult(boolean publishable, List<ValidationIssue> issues, int draftRevision) {}
    public static final class DefinitionNotPublishableException extends RuntimeException {
        private final ValidationResult validation;
        public DefinitionNotPublishableException(ValidationResult validation) {
            super("Agent draft is not publishable");
            this.validation = validation;
        }
        public ValidationResult validation() { return validation; }
    }
}
