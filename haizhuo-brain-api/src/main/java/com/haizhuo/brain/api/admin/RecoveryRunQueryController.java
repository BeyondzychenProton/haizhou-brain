package com.haizhuo.brain.api.admin;

import com.haizhuo.brain.api.error.ApiError;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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

/** 为需要恢复核查的 Run 提供安全的只读管理接口。 */
@RestController
@Validated
@RequestMapping("/api/admin/v1/runs")
public class RecoveryRunQueryController {
    private final RecoveryRunQueryService queries;

    public RecoveryRunQueryController(RecoveryRunQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/recovery")
    public Mono<RecoveryRunQueryService.PageResponse> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) @Min(1) Long userId,
            @RequestParam(required = false) @Min(1) Long employeeId,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) @Size(max = 2048) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        var filter = new RecoveryRunQueryService.Filter(state, userId, employeeId,
                createdFrom, createdTo, cursor, limit);
        return Mono.fromCallable(() -> queries.list(actor.userId(), filter))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{runId}/recovery")
    public Mono<RecoveryRunQueryService.DetailResponse> detail(
            @PathVariable @NotBlank @Size(max = 36) String runId) {
        return Mono.fromCallable(() -> queries.detail(runId)).subscribeOn(Schedulers.boundedElastic());
    }

    @ExceptionHandler(RecoveryRunQueryService.RecoveryRunNotFoundException.class)
    public Mono<ResponseEntity<ApiError>> notFound(RecoveryRunQueryService.RecoveryRunNotFoundException ignored) {
        return error(HttpStatus.NOT_FOUND, "RECOVERY_RUN_NOT_FOUND", "待核查运行不存在。");
    }

    @ExceptionHandler(DataAccessException.class)
    public Mono<ResponseEntity<ApiError>> unavailable(DataAccessException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "RECOVERY_QUERY_UNAVAILABLE", "运行核查查询暂不可用。");
    }

    private static Mono<ResponseEntity<ApiError>> error(HttpStatus status, String code, String message) {
        return Mono.just(ResponseEntity.status(status).body(new ApiError(code, message)));
    }
}
