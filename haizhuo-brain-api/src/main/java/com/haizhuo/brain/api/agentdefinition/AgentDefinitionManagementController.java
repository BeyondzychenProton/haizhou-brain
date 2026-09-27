package com.haizhuo.brain.api.agentdefinition;

import com.haizhuo.brain.platform.employee.AgentDefinitionDraft;
import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.employee.CapabilitySelection;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** The security chain verifies PLATFORM_ADMIN; mutations derive their actor from authentication. */
@RestController
@Validated
@RequestMapping("/api/admin/v1")
public class AgentDefinitionManagementController {
    private final AgentDefinitionManagementService management;

    public AgentDefinitionManagementController(AgentDefinitionManagementService management) {
        this.management = management;
    }

    @GetMapping("/capabilities")
    public Mono<List<CapabilityCatalogEntry>> capabilities() {
        return blocking(management::listCapabilities);
    }

    @GetMapping("/agents/{employeeId}/draft")
    public Mono<AgentDefinitionDraft> draft(@PathVariable @Positive long employeeId) {
        return blocking(() -> management.getDraft(employeeId));
    }

    @PutMapping("/agents/{employeeId}/draft")
    public Mono<AgentDefinitionDraft> updateDraft(@AuthenticationPrincipal AuthenticatedUser actor,
                                                   @PathVariable @Positive long employeeId,
                                                   @Valid @RequestBody DraftRequest request) {
        return blocking(() -> management.saveDraft(employeeId, request.expectedDraftRevision(),
                new AgentDefinitionManagementService.DraftUpdate(request.instructions(), request.modelProvider(),
                        request.modelName(), request.capabilities()), actor.userId().value(), request.reason()));
    }

    @PostMapping("/agents/{employeeId}/validate")
    public Mono<AgentDefinitionManagementService.ValidationResult> validate(@PathVariable @Positive long employeeId) {
        return blocking(() -> management.validateDraft(employeeId));
    }

    @PostMapping("/agents/{employeeId}/publish")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PublishedEmployee> publish(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @PathVariable @Positive long employeeId,
                                            @Valid @RequestBody PublishRequest request) {
        return blocking(() -> management.publish(employeeId, request.expectedDraftRevision(), request.requestId(),
                actor.userId().value(), request.reason()));
    }

    @PutMapping("/capabilities/{capabilityCode}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> setCapabilityStatus(@AuthenticationPrincipal AuthenticatedUser actor,
                                          @PathVariable String capabilityCode,
                                          @Valid @RequestBody CapabilityStatusRequest request) {
        return blocking(() -> {
            management.setCapabilityEnabled(capabilityCode, request.enabled(), actor.userId().value(), request.reason());
            return true;
        }).then();
    }

    @PutMapping("/users/{userId}/capability-grants/{capabilityCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> setUserGrant(@AuthenticationPrincipal AuthenticatedUser actor,
                                   @PathVariable @Positive long userId, @PathVariable String capabilityCode,
                                   @Valid @RequestBody GrantRequest request) {
        return blocking(() -> {
            management.setUserCapabilityGrant(userId, capabilityCode, request.enabled(), actor.userId().value(), request.reason());
            return true;
        }).then();
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record DraftRequest(@Positive int expectedDraftRevision, @NotBlank String instructions,
                               @NotBlank String modelProvider, @NotBlank String modelName,
                               @NotEmpty List<@NotNull CapabilitySelection> capabilities,
                               @NotBlank @Size(max = 500) String reason) {
    }

    public record PublishRequest(@Positive int expectedDraftRevision, @NotBlank @Size(max = 128) String requestId,
                                 @NotBlank @Size(max = 500) String reason) {
    }

    public record CapabilityStatusRequest(boolean enabled, @NotBlank @Size(max = 500) String reason) {
    }

    public record GrantRequest(boolean enabled, @NotBlank @Size(max = 500) String reason) {
    }
}
