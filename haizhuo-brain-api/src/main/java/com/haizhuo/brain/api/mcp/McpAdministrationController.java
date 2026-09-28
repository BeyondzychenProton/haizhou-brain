package com.haizhuo.brain.api.mcp;

import com.haizhuo.brain.platform.mcp.McpAdministrationService;
import com.haizhuo.brain.platform.mcp.McpConnection;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** 安全过滤链将 /api/admin/ 下的接口限制为 PLATFORM_ADMIN 角色。 */
@RestController
@Validated
@RequestMapping("/api/admin/v1/mcp/connections")
public class McpAdministrationController {
    private final McpAdministrationService service;

    public McpAdministrationController(McpAdministrationService service) { this.service = service; }

    @GetMapping
    public Mono<List<McpConnection>> list() { return blocking(service::connections); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<McpConnection> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @Valid @RequestBody CreateRequest request) {
        return blocking(() -> service.create(request.code(), request.displayName(), request.endpoint(),
                actor.userId().value(), request.reason()));
    }

    @PutMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> status(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable @Positive long id,
                             @Valid @RequestBody StatusRequest request) {
        return blocking(() -> {
            service.setEnabled(id, request.enabled(), actor.userId().value(), request.reason());
            return true;
        }).then();
    }

    @PostMapping("/{id}/discover")
    public Mono<McpAdministrationService.Discovery> discover(@AuthenticationPrincipal AuthenticatedUser actor,
                                                              @PathVariable @Positive long id) {
        return blocking(() -> service.discover(id, actor.userId()));
    }

    @GetMapping("/{id}/discoveries/diff")
    public Mono<McpAdministrationService.DiscoveryDiff> recentDiff(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable @Positive long id) {
        return blocking(() -> service.recentDiff(id, actor.userId().value()));
    }

    @PostMapping("/{id}/tools/{remoteName}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ApprovedRevision> approve(@AuthenticationPrincipal AuthenticatedUser actor,
                                          @PathVariable @Positive long id, @PathVariable String remoteName,
                                          @Valid @RequestBody ApprovalRequest request) {
        return blocking(() -> new ApprovedRevision(service.approve(id, request.snapshotId(), remoteName,
                new McpAdministrationService.Approval(request.capabilityCode(), request.revision(),
                        request.modelToolName(), request.displayName(), request.description(),
                        request.readOnly(), request.requiresConfirmation()), actor.userId().value(),
                request.reason())));
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateRequest(@NotBlank String code, @NotBlank String displayName,
                                @NotBlank String endpoint, @NotBlank @Size(max = 500) String reason) { }
    public record StatusRequest(boolean enabled, @NotBlank @Size(max = 500) String reason) { }
    public record ApprovalRequest(@Positive long snapshotId, @NotBlank String capabilityCode,
                                  @NotBlank String revision, @NotBlank String modelToolName,
                                  @NotBlank String displayName, @NotBlank String description,
                                  boolean readOnly, boolean requiresConfirmation,
                                  @NotBlank @Size(max = 500) String reason) { }
    public record ApprovedRevision(long capabilityRevisionId) { }
}
