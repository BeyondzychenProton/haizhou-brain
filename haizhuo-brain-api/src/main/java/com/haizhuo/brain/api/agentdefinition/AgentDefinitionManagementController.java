package com.haizhuo.brain.api.agentdefinition;

import com.haizhuo.brain.platform.employee.AgentDefinitionDraft;
import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.employee.CapabilitySelection;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 首轮本地 Mock 管理 API；当前无管理员登录，只允许 loopback Mock 配置启用。 */
@RestController
@Validated
@Profile("meeting-mock | test")
@RequestMapping("/api/admin/v1")
public class AgentDefinitionManagementController {
    private final AgentDefinitionManagementService management;
    private final long actorId;

    public AgentDefinitionManagementController(AgentDefinitionManagementService management,
            @Value("${haizhuo.brain.meeting-room.admin-actor-id:1001}") long actorId) {
        this.management = management;
        this.actorId = actorId;
    }

    @GetMapping("/capabilities")
    public List<CapabilityCatalogEntry> capabilities() { return management.listCapabilities(); }

    @GetMapping("/agents/{employeeId}/draft")
    public AgentDefinitionDraft draft(@PathVariable @Positive long employeeId) { return management.getDraft(employeeId); }

    @PutMapping("/agents/{employeeId}/draft")
    public AgentDefinitionDraft updateDraft(@PathVariable @Positive long employeeId,
                                             @Valid @RequestBody DraftRequest request) {
        return management.saveDraft(employeeId, request.expectedDraftRevision(), new AgentDefinitionManagementService.DraftUpdate(
                request.instructions(), request.modelProvider(), request.modelName(), request.capabilities()), actorId);
    }

    @PostMapping("/agents/{employeeId}/validate")
    public AgentDefinitionManagementService.ValidationResult validate(@PathVariable @Positive long employeeId) {
        return management.validateDraft(employeeId);
    }

    @PostMapping("/agents/{employeeId}/publish")
    @ResponseStatus(HttpStatus.CREATED)
    public PublishedEmployee publish(@PathVariable @Positive long employeeId, @Valid @RequestBody PublishRequest request) {
        return management.publish(employeeId, request.expectedDraftRevision(), request.requestId(), actorId);
    }

    @PutMapping("/capabilities/{capabilityCode}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCapabilityStatus(@PathVariable String capabilityCode, @Valid @RequestBody CapabilityStatusRequest request) {
        management.setCapabilityEnabled(capabilityCode, request.enabled(), actorId, request.reason());
    }

    @PutMapping("/users/{userId}/capability-grants/{capabilityCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setUserGrant(@PathVariable @Positive long userId, @PathVariable String capabilityCode,
                             @Valid @RequestBody GrantRequest request) {
        management.setUserCapabilityGrant(userId, capabilityCode, request.enabled(), actorId);
    }

    public record DraftRequest(@Positive int expectedDraftRevision, @NotBlank String instructions,
                               @NotBlank String modelProvider, @NotBlank String modelName,
                               @NotEmpty List<@NotNull CapabilitySelection> capabilities) {}
    public record PublishRequest(@Positive int expectedDraftRevision, @NotBlank String requestId) {}
    public record CapabilityStatusRequest(boolean enabled, @NotBlank String reason) {}
    public record GrantRequest(boolean enabled) {}
}
