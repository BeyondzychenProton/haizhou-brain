package com.haizhuo.brain.api.session;

import com.haizhuo.brain.api.error.ApiError;
import com.haizhuo.brain.platform.run.RunProgressProjection;
import com.haizhuo.brain.platform.run.RunProgressQueryService;
import com.haizhuo.brain.platform.run.RunProgressQueryService.RunProgressNotFoundException;
import com.haizhuo.brain.platform.run.RunWorkItemDetail;
import com.haizhuo.brain.platform.run.RunWorkItemPage;
import com.haizhuo.brain.platform.run.RunWorkItemResult;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpHeaders;
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

/** 通过属主身份校验提供安全的协作状态投影。 */
@RestController
@Validated
@RequestMapping("/api/v1/sessions/runs/{runId}")
public class RunProgressController {
    private final RunProgressQueryService progress;

    public RunProgressController(RunProgressQueryService progress) { this.progress = progress; }

    @GetMapping("/progress")
    public Mono<ResponseEntity<RunProgressProjection>> getProgress(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable @Size(max = 64) String runId,
            @RequestParam(required = false) @Size(max = 80) String ifVersion) {
        return blocking(() -> {
            RunProgressQueryService.ProgressResult result = progress.progress(user.userId(), runId, ifVersion);
            HttpHeaders headers = privateNoStoreHeaders();
            headers.setETag('"' + result.projection().version() + '"');
            if (result.notModified()) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
            return ResponseEntity.ok().headers(headers).body(result.projection());
        });
    }

    @GetMapping("/work-items")
    public Mono<ResponseEntity<WorkItemPageResponse>> workItems(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable @Size(max = 64) String runId,
            @RequestParam(required = false) @Size(max = 2048) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return blocking(() -> {
            RunWorkItemPage page = progress.workItems(user.userId(), runId, cursor, limit);
            return ResponseEntity.ok().headers(privateNoStoreHeaders()).body(
                    new WorkItemPageResponse(page.items(), page.nextCursor(), page.hasMore()));
        });
    }

    @GetMapping("/work-items/{workItemRef}")
    public Mono<ResponseEntity<RunWorkItemDetail>> workItem(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable @Size(max = 64) String runId,
            @PathVariable @Size(max = 64) String workItemRef) {
        return blocking(() -> progress.workItem(user.userId(), runId, workItemRef)
                .map(item -> ResponseEntity.ok().headers(privateNoStoreHeaders()).body(item))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build()));
    }

    @GetMapping("/work-items/{workItemRef}/revisions/{assignmentRevision}/result")
    public Mono<ResponseEntity<RunWorkItemResult>> workItemResult(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable @Size(max = 64) String runId,
            @PathVariable @Size(max = 64) String workItemRef,
            @PathVariable @Min(1) int assignmentRevision) {
        return blocking(() -> progress.workItemResult(user.userId(), runId, workItemRef, assignmentRevision)
                .map(result -> ResponseEntity.ok().headers(privateNoStoreHeaders()).body(result))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build()));
    }

    @ExceptionHandler(RunProgressNotFoundException.class)
    public Mono<ResponseEntity<ApiError>> notFound(RunProgressNotFoundException ignored) {
        return error(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "运行记录不存在");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ApiError>> invalidRequest(IllegalArgumentException ignored) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PROGRESS_QUERY", "协作进度查询参数无效");
    }

    private static HttpHeaders privateNoStoreHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        headers.set(HttpHeaders.PRAGMA, "no-cache");
        return headers;
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<ResponseEntity<ApiError>> error(HttpStatus status, String code, String message) {
        return Mono.just(ResponseEntity.status(status).body(new ApiError(code, message)));
    }

    public record WorkItemPageResponse(List<RunProgressProjection.WorkItemSummary> items,
                                       String nextCursor, boolean hasMore) {
        public WorkItemPageResponse { items = List.copyOf(items); }
    }
}
