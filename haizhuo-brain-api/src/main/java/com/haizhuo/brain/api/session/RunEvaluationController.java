package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.observability.LangfuseEvaluationService;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 受保护的 Run 评分入口。先通过平台 SessionApplicationService 校验 Run 归属，
 * 再把脱敏后的数值评分异步写入 Langfuse；观测未启用时返回 accepted=false。
 */
@RestController
@Validated
@RequestMapping("/api/v1/sessions/runs/{runId}/evaluations")
public class RunEvaluationController {
    private final SessionApplicationService sessions;
    private final LangfuseEvaluationService evaluations;

    public RunEvaluationController(SessionApplicationService sessions,
                                   LangfuseEvaluationService evaluations) {
        this.sessions = sessions;
        this.evaluations = evaluations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<EvaluationResponse> submit(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable String runId,
                                           @Valid @RequestBody EvaluationRequest request) {
        return Mono.fromCallable(() -> {
            RunId id = new RunId(runId);
            // 评分不能成为越权查询 Langfuse trace 的旁路。
            sessions.getRun(id, user.userId());
            boolean accepted = evaluations.submitNumeric(id, request.observationId(), request.name(),
                    request.value(), request.comment());
            return new EvaluationResponse(runId, evaluations.traceId(id), request.name(),
                    accepted, Instant.now());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public record EvaluationRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String name,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double value,
            @Size(max = 64) String observationId,
            @Size(max = 1000) String comment) {
    }

    public record EvaluationResponse(String runId, String traceId, String name,
                                     boolean accepted, Instant queuedAt) {
    }
}
