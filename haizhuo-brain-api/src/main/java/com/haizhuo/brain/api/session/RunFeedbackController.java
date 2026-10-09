package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.observability.LangfuseEvaluationService;
import com.haizhuo.brain.platform.run.RunFeedback;
import com.haizhuo.brain.platform.run.RunFeedbackPreconditionException;
import com.haizhuo.brain.platform.run.RunFeedbackService;
import com.haizhuo.brain.platform.session.SessionHistoryNotFoundException;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 先将反馈保存为平台事实，再尽力向观测队列提交一次。 */
@RestController
@Validated
@RequestMapping("/api/v1/sessions/runs/{runId}/feedback")
public class RunFeedbackController {
    private static final String SCORE_NAME = "user.satisfaction";
    private final RunFeedbackService feedback;
    private final NumericScoreSubmitter evaluations;

    @Autowired
    public RunFeedbackController(RunFeedbackService feedback, LangfuseEvaluationService evaluations) {
        this(feedback, evaluations::submitNumeric);
    }

    RunFeedbackController(RunFeedbackService feedback, NumericScoreSubmitter evaluations) {
        this.feedback = feedback;
        this.evaluations = evaluations;
    }

    @PostMapping
    public Mono<ResponseEntity<FeedbackResponse>> submit(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @PathVariable @Size(max = 36) String runId,
                                                         @Valid @RequestBody FeedbackRequest request) {
        return blocking(() -> {
            var owner = user.userId();
            RunFeedbackService.Submission submission = feedback.submit(owner, runId, request.clientRequestId(),
                    request.value(), request.comment());
            RunFeedback item = submission.feedback();
            if (submission.created()) {
                try {
                    boolean accepted = evaluations.submitNumeric(new RunId(runId), null, SCORE_NAME,
                            request.value(), request.comment());
                    item = feedback.updateExportDisposition(owner, runId, item.feedbackId(), accepted
                            ? RunFeedback.ExportDisposition.ACCEPTED_NOT_CONFIRMED
                            : RunFeedback.ExportDisposition.NOT_ACCEPTED);
                } catch (RuntimeException exportOrStatusWriteUnknown) {
                    // 用户反馈事实已持久化；队列结果未确认时，继续保留未知状态。
                }
            }
            HttpStatus status = submission.created() ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(status).body(response(item));
        });
    }

    @GetMapping
    public Mono<PageResponse<FeedbackResponse>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @PathVariable @Size(max = 36) String runId,
                                                    @RequestParam(required = false) @Size(max = 1024) String cursor,
                                                    @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return blocking(() -> {
            RunFeedbackService.Page page = feedback.list(user.userId(), runId, cursor, limit);
            return new PageResponse<>(page.items().stream().map(RunFeedbackController::response).toList(),
                    page.nextCursor(), page.hasMore());
        });
    }

    @ExceptionHandler(SessionHistoryNotFoundException.class)
    public Mono<ResponseEntity<ApiError>> notFound(SessionHistoryNotFoundException ignored) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "运行记录不存在");
    }

    @ExceptionHandler(RunFeedbackPreconditionException.class)
    public Mono<ResponseEntity<ApiError>> precondition(RunFeedbackPreconditionException ignored) {
        return error(HttpStatus.CONFLICT, "PRECONDITION_REQUIRED", "运行尚无可评价的完整结果");
    }

    private static FeedbackResponse response(RunFeedback item) {
        return new FeedbackResponse(item.feedbackId(), item.runId().value(), item.value(), item.comment(),
                item.createdAt(), item.exportDisposition().name());
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<ResponseEntity<ApiError>> error(HttpStatus status, String code, String message) {
        return Mono.just(ResponseEntity.status(status).body(new ApiError(code, message)));
    }

    public record FeedbackRequest(@NotBlank @Size(max = 128) String clientRequestId,
                                  @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double value,
                                  @Size(max = 1000) String comment) { }

    public record FeedbackResponse(String feedbackId, String runId, double value, String comment,
                                   Instant createdAt, String exportDisposition) { }

    public record PageResponse<T>(List<T> items, String nextCursor, boolean hasMore) {
        public PageResponse { items = List.copyOf(items); }
    }

    @FunctionalInterface
    interface NumericScoreSubmitter {
        boolean submitNumeric(RunId runId, String observationId, String name, double value, String comment);
    }

    public record ApiError(String code, String message) { }
}
