package com.haizhuo.brain.api.admin;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunRecoveryAdministrationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Admin-only by the shared /api/admin/** security rule; evidence is recorded as a reference, never a payload. */
@RestController
@Validated
@Profile("!test")
@RequestMapping("/api/admin/v1/runs")
public class RunRecoveryAdministrationController {
    private final RunRecoveryAdministrationService recovery;

    public RunRecoveryAdministrationController(RunRecoveryAdministrationService recovery) {
        this.recovery = recovery;
    }

    @PostMapping("/{runId}/recovery/terminate")
    public Mono<TerminationResponse> terminate(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @PathVariable @NotBlank @Size(max = 36) String runId,
                                               @Valid @RequestBody TerminationRequest request) {
        return Mono.fromCallable(() -> response(recovery.terminate(new RunId(runId), actor.userId(),
                        request.expectedFenceToken(), request.requestId(), request.stopEvidenceReference(), request.reason()),
                        request.requestId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static TerminationResponse response(AgentRun run, String requestId) {
        return new TerminationResponse(run.id().value(), run.state().name(), run.finishedAt(), requestId);
    }

    public record TerminationRequest(@Positive long expectedFenceToken,
                                     @NotBlank @Size(max = 128) String requestId,
                                     @NotBlank @Size(max = 512) String stopEvidenceReference,
                                     @NotBlank @Size(max = 500) String reason) { }

    public record TerminationResponse(String runId, String state, Instant finishedAt, String requestId) { }
}
