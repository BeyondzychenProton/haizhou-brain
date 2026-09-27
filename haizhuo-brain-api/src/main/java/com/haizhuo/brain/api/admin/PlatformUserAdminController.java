package com.haizhuo.brain.api.admin;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.security.identity.ActivationCredential;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformUserManagementService;
import com.haizhuo.brain.security.identity.PlatformUserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** The role and actor are sourced from the security context; request bodies cannot self-elevate. */
@RestController
@RequestMapping("/api/admin/v1/users")
public class PlatformUserAdminController {
    private final PlatformUserManagementService managementService;

    public PlatformUserAdminController(PlatformUserManagementService managementService) {
        this.managementService = managementService;
    }

    @PostMapping
    public Mono<CreatedUserResponse> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @Valid @RequestBody CreateUserRequest request) {
        return blocking(() -> managementService.createPendingUser(actor.userId(), request.mobile(), request.reason()))
                .map(created -> new CreatedUserResponse(created.user().id().value(), created.user().mobileNormalized(),
                        created.activationCredential().token(), created.activationCredential().expiresAt()));
    }

    @PostMapping("/{userId}/activation")
    public Mono<ActivationCredentialResponse> reissueActivation(@AuthenticationPrincipal AuthenticatedUser actor,
                                                                 @PathVariable long userId,
                                                                 @Valid @RequestBody ReasonRequest request) {
        return blocking(() -> managementService.reissueActivation(actor.userId(), new UserId(userId), request.reason()))
                .map(PlatformUserAdminController::activationResponse);
    }

    @PutMapping("/{userId}/roles/PLATFORM_ADMIN")
    public Mono<Void> grantAdministrator(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long userId,
                                         @Valid @RequestBody ReasonRequest request) {
        return blocking(() -> {
            managementService.grantPlatformAdmin(actor.userId(), new UserId(userId), request.reason());
            return true;
        }).then();
    }

    @DeleteMapping("/{userId}/roles/PLATFORM_ADMIN")
    public Mono<Void> revokeAdministrator(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long userId,
                                          @Valid @RequestBody ReasonRequest request) {
        return blocking(() -> {
            managementService.revokePlatformAdmin(actor.userId(), new UserId(userId), request.reason());
            return true;
        }).then();
    }

    @PutMapping("/{userId}/status")
    public Mono<Void> changeStatus(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long userId,
                                   @Valid @RequestBody StatusRequest request) {
        return blocking(() -> {
            managementService.changeStatus(actor.userId(), new UserId(userId), request.status(), request.reason());
            return true;
        }).then();
    }

    private static ActivationCredentialResponse activationResponse(ActivationCredential credential) {
        return new ActivationCredentialResponse(credential.token(), credential.expiresAt());
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateUserRequest(@NotBlank @Size(max = 32) String mobile,
                                    @NotBlank @Size(max = 500) String reason) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record StatusRequest(@NotNull PlatformUserStatus status, @NotBlank @Size(max = 500) String reason) {
    }

    /** The one-time credential is intentionally returned exactly once to the authenticated administrator. */
    public record CreatedUserResponse(long userId, String mobileNormalized, String activationToken, Instant activationExpiresAt) {
    }

    public record ActivationCredentialResponse(String activationToken, Instant expiresAt) {
    }
}
