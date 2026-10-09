package com.haizhuo.brain.api.agentdefinition;

import com.haizhuo.brain.platform.employee.AgentDefinitionDraft;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationService;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.CreatedEmployee;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeePage;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeeSummary;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeeVersionDetail;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore.EmployeeVersionPage;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 仅管理员可用的员工生命周期与不可变版本查询接口。 */
@RestController
@Validated
@RequestMapping("/api/admin/v1/agents")
public class EmployeeAdministrationController {
    private final EmployeeAdministrationService administration;

    public EmployeeAdministrationController(EmployeeAdministrationService administration) {
        this.administration = administration;
    }

    @GetMapping("/runtime-options")
    public Mono<EmployeeAdministrationService.RuntimeOptions> runtimeOptions() {
        return blocking(administration::runtimeOptions);
    }

    @GetMapping
    public Mono<EmployeePage> employees(@RequestParam(required = false) String query,
                                        @RequestParam(required = false) Boolean enabled,
                                        @RequestParam(required = false) Boolean published,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) @Positive Integer limit) {
        return blocking(() -> administration.listEmployees(query, enabled, published, cursor, limit));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CreatedEmployee> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                        @Valid @RequestBody CreateRequest request) {
        return blocking(() -> administration.createEmployee(request.employeeCode(), request.displayName(),
                request.requestId(), actor.userId().value(), request.reason()));
    }

    @PatchMapping("/{employeeId}")
    public Mono<EmployeeSummary> update(@AuthenticationPrincipal AuthenticatedUser actor,
                                        @PathVariable @Positive long employeeId,
                                        @Valid @RequestBody UpdateRequest request) {
        return blocking(() -> administration.updateEmployee(employeeId, request.expectedRowVersion(),
                request.displayName(), request.requestId(), actor.userId().value(), request.reason()));
    }

    @PutMapping("/{employeeId}/status")
    public Mono<EmployeeSummary> setStatus(@AuthenticationPrincipal AuthenticatedUser actor,
                                           @PathVariable @Positive long employeeId,
                                           @Valid @RequestBody StatusRequest request) {
        return blocking(() -> administration.setEmployeeEnabled(employeeId, request.expectedRowVersion(),
                request.enabled(), request.requestId(), actor.userId().value(), request.reason()));
    }

    @GetMapping("/{employeeId}/versions")
    public Mono<EmployeeVersionPage> versions(@PathVariable @Positive long employeeId,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) @Positive Integer limit) {
        return blocking(() -> {
            try { return administration.listVersions(employeeId, cursor, limit); }
            catch (IllegalArgumentException missing) {
                if ("Digital employee not found".equals(missing.getMessage()))
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
                throw missing;
            }
        });
    }

    @GetMapping("/{employeeId}/versions/{versionId}")
    public Mono<EmployeeVersionDetail> version(@PathVariable @Positive long employeeId,
                                              @PathVariable @Positive long versionId) {
        return blocking(() -> {
            try { return administration.findVersion(employeeId, versionId); }
            catch (IllegalArgumentException missing) {
                if ("Employee version was not found".equals(missing.getMessage()))
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee version not found");
                throw missing;
            }
        });
    }

    @PostMapping("/{employeeId}/draft/restore")
    public Mono<AgentDefinitionDraft> restore(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable @Positive long employeeId,
                                              @Valid @RequestBody RestoreRequest request) {
        return blocking(() -> administration.restoreDraft(employeeId, request.sourceVersionId(),
                request.expectedDraftRevision(), request.requestId(), actor.userId().value(), request.reason()));
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateRequest(@NotBlank @Size(max = 64) String employeeCode,
                                @NotBlank @Size(max = 128) String displayName,
                                @NotBlank @Size(max = 128) String requestId,
                                @NotBlank @Size(max = 500) String reason) {}

    public record UpdateRequest(@PositiveOrZero long expectedRowVersion,
                                @NotBlank @Size(max = 128) String displayName,
                                @NotBlank @Size(max = 128) String requestId,
                                @NotBlank @Size(max = 500) String reason) {}

    public record StatusRequest(@PositiveOrZero long expectedRowVersion, boolean enabled,
                                @NotBlank @Size(max = 128) String requestId,
                                @NotBlank @Size(max = 500) String reason) {}

    public record RestoreRequest(@Positive long sourceVersionId,
                                 @Positive int expectedDraftRevision,
                                 @NotBlank @Size(max = 128) String requestId,
                                 @NotBlank @Size(max = 500) String reason) {}
}
