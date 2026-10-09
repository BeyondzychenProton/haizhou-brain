package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.platform.session.SessionHistoryNotFoundException;
import com.haizhuo.brain.platform.session.SessionHistoryQuery;
import com.haizhuo.brain.platform.session.SessionHistoryQueryService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.platform.tool.ToolApprovalService;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
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

/** 新增 keyset 历史查询；旧数组接口保持原样。 */
@RestController
@Validated
@RequestMapping("/api/v1/sessions")
public final class SessionHistoryController {
    private final SessionHistoryQueryService history;
    private final SessionApplicationService sessions;
    private final ToolApprovalService toolApprovals;

    public SessionHistoryController(SessionHistoryQueryService history, SessionApplicationService sessions,
                                    ToolApprovalService toolApprovals) {
        this.history = history;
        this.sessions = sessions;
        this.toolApprovals = toolApprovals;
    }

    @GetMapping("/page")
    public Mono<PageResponse<SessionItemResponse>> sessions(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @RequestParam(required = false) @Min(1) Long employeeId,
                                                            @RequestParam(required = false) @Size(max = 24) String status,
                                                            @RequestParam(required = false) @Size(max = 2048) String cursor,
                                                            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return blocking(() -> page(history.sessions(user.userId(),
                new SessionHistoryQueryService.SessionFilter(employeeId, status, cursor, limit)),
                SessionHistoryController::session));
    }

    @GetMapping("/{sessionId}/runs/page")
    public Mono<PageResponse<RunItemResponse>> runs(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @PathVariable @Size(max = 36) String sessionId,
                                                    @RequestParam(required = false) @Size(max = 32) String state,
                                                    @RequestParam(required = false) @Size(max = 2048) String cursor,
                                                    @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return blocking(() -> page(history.runs(user.userId(), sessionId,
                new SessionHistoryQueryService.RunFilter(state, cursor, limit)), SessionHistoryController::run));
    }

    @GetMapping("/{sessionId}/results/page")
    public Mono<PageResponse<ResultItemResponse>> results(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @PathVariable @Size(max = 36) String sessionId,
                                                          @RequestParam(required = false) @Size(max = 2048) String cursor,
                                                          @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return blocking(() -> page(history.results(user.userId(), sessionId,
                new SessionHistoryQueryService.ResultFilter(cursor, limit)), SessionHistoryController::result));
    }

    /** 安全投影历史工具记录；旧审批接口继续保留兼容的完整结构。 */
    @GetMapping("/runs/{runId}/tool-summaries")
    public Mono<List<ToolSummaryResponse>> tools(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @PathVariable @Size(max = 36) String runId) {
        return blocking(() -> {
            RunId id;
            try {
                id = new RunId(runId);
                sessions.getRun(id, user.userId());
            } catch (IllegalArgumentException notOwnedOrMissing) {
                throw new SessionHistoryNotFoundException();
            }
            return toolApprovals.listForRun(id, user.userId()).stream()
                    .map(execution -> toolSummary(execution, toolApprovals.approvalOf(execution.id())
                            .map(item -> item.decision().name()).orElse(null)))
                    .toList();
        });
    }

    @ExceptionHandler(SessionHistoryNotFoundException.class)
    public Mono<ResponseEntity<ApiError>> notFound(SessionHistoryNotFoundException ignored) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "会话不存在");
    }

    @ExceptionHandler(DataAccessException.class)
    public Mono<ResponseEntity<ApiError>> unavailable(DataAccessException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "历史记录暂不可用");
    }

    private static <T, R> PageResponse<R> page(SessionHistoryQuery.Page<T> page,
                                               java.util.function.Function<T, R> map) {
        return new PageResponse<>(page.items().stream().map(map).toList(), page.nextCursor(), page.hasMore());
    }

    private static SessionItemResponse session(SessionHistoryQuery.SessionItem item) {
        return new SessionItemResponse(item.sessionId(), item.employeeId(), item.definitionVersionId(), item.status(),
                item.createdAt(), item.lastActiveAt());
    }

    private static RunItemResponse run(SessionHistoryQuery.RunItem item) {
        return new RunItemResponse(item.runId(), item.sessionId(), item.state(), item.definitionVersionId(),
                item.createdAt(), item.queuePosition(), item.executorRoleId(), item.executorEmployeeId(),
                item.executorDefinitionVersionId(), item.mode());
    }

    private static ResultItemResponse result(SessionHistoryQuery.ResultItem item) {
        return new ResultItemResponse(item.resultId(), item.runId(), item.kind(), item.mediaType(), item.bodySha256(),
                item.byteSize(), item.createdAt(), item.executorRoleId(), item.executorEmployeeId(),
                item.executorDefinitionVersionId());
    }

    private static ToolSummaryResponse toolSummary(PlatformToolExecution execution, String approvalDecision) {
        return new ToolSummaryResponse(execution.id(), execution.toolName(), execution.state().name(),
                approvalDecision, execution.createdAt(), execution.updatedAt());
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<ResponseEntity<ApiError>> error(HttpStatus status, String code, String message) {
        return Mono.just(ResponseEntity.status(status).body(new ApiError(code, message)));
    }

    public record PageResponse<T>(List<T> items, String nextCursor, boolean hasMore) {
        public PageResponse { items = List.copyOf(items); }
    }

    public record SessionItemResponse(String sessionId, long employeeId, Long definitionVersionId, String status,
                                      Instant createdAt, Instant lastActiveAt) { }

    public record RunItemResponse(String runId, String sessionId, String state, long definitionVersionId,
                                  Instant createdAt, int queuePosition, String executorRoleId,
                                  long executorEmployeeId, long executorDefinitionVersionId, String mode) { }

    public record ResultItemResponse(String resultId, String runId, String kind, String mediaType,
                                     String bodySha256, long byteSize, Instant createdAt, String executorRoleId,
                                     Long executorEmployeeId, Long executorDefinitionVersionId) { }

    public record ToolSummaryResponse(String toolExecutionId, String toolName, String state,
                                      String approvalDecision, Instant createdAt, Instant updatedAt) { }

    public record ApiError(String code, String message) { }
}
