package com.haizhuo.brain.api.channel;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.channel.ChannelDeliveryAdministrationQuery;
import com.haizhuo.brain.platform.channel.ChannelDeliveryAdministrationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 仅管理员可查看已有 Delivery 事实；此处不提供 claim、重试或核查命令。 */
@RestController
@Validated
@RequestMapping("/api/admin/v1/channels/deliveries")
public class ChannelDeliveryAdministrationController {
    private final ChannelDeliveryAdministrationService administration;

    public ChannelDeliveryAdministrationController(ChannelDeliveryAdministrationService administration) {
        this.administration = administration;
    }

    @GetMapping
    public Mono<DeliveryPageResponse> list(@AuthenticationPrincipal AuthenticatedUser actor,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) @Size(max = 64) String bindingId,
                                           @RequestParam(required = false) @Size(max = 32) String provider,
                                           @RequestParam(required = false) @Size(max = 64) String runId,
                                           @RequestParam(required = false) Instant createdFrom,
                                           @RequestParam(required = false) Instant createdTo,
                                           @RequestParam(required = false) @Size(max = 2048) String cursor,
                                           @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        var filter = new ChannelDeliveryAdministrationService.Filter(state, bindingId, provider, runId,
                createdFrom, createdTo, cursor, limit);
        return Mono.fromCallable(() -> page(administration.list(actor.userId(), filter)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{deliveryId}")
    public Mono<DeliveryDetailResponse> detail(@PathVariable @Size(max = 64) String deliveryId) {
        return Mono.fromCallable(() -> administration.detail(deliveryId)
                        .map(ChannelDeliveryAdministrationController::detail)
                        .orElseThrow(DeliveryNotFoundException::new))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @ExceptionHandler(DeliveryNotFoundException.class)
    public Mono<ResponseEntity<ApiError>> notFound(DeliveryNotFoundException ignored) {
        return error(HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND", "投递记录不存在");
    }

    @ExceptionHandler(DataAccessException.class)
    public Mono<ResponseEntity<ApiError>> unavailable(DataAccessException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DELIVERY_QUERY_UNAVAILABLE", "投递查询暂不可用");
    }

    private static DeliveryPageResponse page(ChannelDeliveryAdministrationQuery.Page page) {
        return new DeliveryPageResponse(page.items().stream().map(ChannelDeliveryAdministrationController::summary)
                .toList(), page.nextCursor(), page.hasMore());
    }

    private static DeliveryDetailResponse detail(ChannelDeliveryAdministrationQuery.DeliveryDetail detail) {
        return new DeliveryDetailResponse(summary(detail.summary()), detail.runState(),
                detail.attemptsHistory().stream().map(a -> new AttemptHistoryResponse(a.attemptNo(),
                        a.claimGeneration(), a.startedAt(), a.finishedAt(), a.status(), a.safeErrorCode())).toList(),
                detail.attemptsHistoryState(), detail.verifications().stream()
                        .map(v -> new VerificationResponse(v.decision(), v.occurredAt(),
                                v.evidenceReferenceLabel())).toList());
    }

    private static DeliverySummaryResponse summary(ChannelDeliveryAdministrationQuery.DeliverySummary summary) {
        return new DeliverySummaryResponse(summary.deliveryId(), summary.runId(), summary.sessionId(),
                summary.bindingId(), summary.provider(), summary.state(), summary.attempts(), summary.resultId(),
                summary.contentPreview(), summary.externalMessageId(), summary.lastErrorCode(), summary.createdAt(),
                summary.updatedAt(), summary.sendingExpiresAt(), summary.evidenceSource(), summary.revision());
    }

    private static Mono<ResponseEntity<ApiError>> error(HttpStatus status, String code, String message) {
        return Mono.just(ResponseEntity.status(status).body(new ApiError(code, message)));
    }

    public record DeliveryPageResponse(List<DeliverySummaryResponse> items, String nextCursor, boolean hasMore) { }

    public record DeliverySummaryResponse(String deliveryId, String runId, String sessionId, String bindingId,
                                          String provider, String state, int attempts, String resultId,
                                          String contentPreview, String externalMessageId, String lastErrorCode,
                                          Instant createdAt, Instant updatedAt, Instant sendingExpiresAt,
                                          String evidenceSource, long revision) { }

    public record DeliveryDetailResponse(DeliverySummaryResponse summary, String runState,
                                         List<AttemptHistoryResponse> attemptsHistory,
                                         String attemptsHistoryState, List<VerificationResponse> verifications) { }

    public record AttemptHistoryResponse(int attemptNo, long claimGeneration, Instant startedAt, Instant finishedAt,
                                         String status, String safeErrorCode) { }
    public record VerificationResponse(String decision, String occurredAt, String evidenceReferenceLabel) { }
    public record ApiError(String code, String message) { }

    private static final class DeliveryNotFoundException extends RuntimeException { }
}
