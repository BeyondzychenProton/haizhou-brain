package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.session.AgentSession;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.SessionTimelineItem;
import com.haizhuo.brain.platform.run.RunGuidance;
import com.haizhuo.brain.platform.tool.ToolApprovalService;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 会话归属完全由已认证主体推导得出。 */
@RestController
@Validated
@RequestMapping("/api/v1/sessions")
public class SessionController {
    private final SessionApplicationService sessions;
    private final ToolApprovalService toolApprovals;

    public SessionController(SessionApplicationService sessions, ToolApprovalService toolApprovals) {
        this.sessions = sessions;
        this.toolApprovals = toolApprovals;
    }

    @GetMapping
    public Mono<List<SessionResponse>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                            @RequestParam(defaultValue = "20") int limit) {
        return blocking(() -> sessions.list(user.userId(), limit).stream().map(SessionController::response).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SessionResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                        @Valid @RequestBody CreateSessionRequest request) {
        return blocking(() -> response(sessions.create(user.userId(), request.employeeId())));
    }

    @GetMapping("/{sessionId}")
    public Mono<SessionResponse> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String sessionId) {
        return blocking(() -> response(sessions.get(new SessionId(sessionId), user.userId())));
    }

    @GetMapping("/{sessionId}/timeline")
    public Mono<List<SessionTimelineItem>> timeline(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String sessionId,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return blocking(() -> sessions.timeline(new SessionId(sessionId), user.userId(), limit));
    }

    @GetMapping("/{sessionId}/runs")
    public Mono<List<RunResponse>> runs(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String sessionId,
                                        @RequestParam(defaultValue = "20") int limit) {
        return blocking(() -> sessions.runs(new SessionId(sessionId), user.userId(), limit).stream()
                .map(run -> runResponse(run, sessions.queuePosition(run.id(), user.userId()))).toList());
    }

    @PostMapping("/{sessionId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RunResponse> createRun(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String sessionId,
                                       @Valid @RequestBody CreateRunRequest request) {
        return blocking(() -> {
            AgentRun run = sessions.createRun(new SessionId(sessionId), user.userId(), request.clientRequestId(), request.input().trim());
            return runResponse(run, sessions.queuePosition(run.id(), user.userId()));
        });
    }

    @GetMapping("/runs/{runId}")
    public Mono<RunResponse> getRun(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String runId) {
        return blocking(() -> {
            AgentRun run = sessions.getRun(new RunId(runId), user.userId());
            return runResponse(run, sessions.queuePosition(run.id(), user.userId()));
        });
    }

    @GetMapping("/runs/{runId}/events")
    public Mono<java.util.List<RunEvent>> events(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @PathVariable String runId,
                                                 @RequestParam(defaultValue = "0") int after,
                                                 @RequestParam(defaultValue = "200") int limit) {
        return blocking(() -> sessions.events(new RunId(runId), user.userId(), after, limit));
    }

    @PostMapping("/runs/{runId}/cancel")
    public Mono<RunResponse> cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String runId) {
        return blocking(() -> runResponse(sessions.cancel(new RunId(runId), user.userId()), 0));
    }

    @PostMapping("/runs/{runId}/guidance")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<GuidanceResponse> guide(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String runId,
                                        @Valid @RequestBody GuidanceRequest request) {
        return blocking(() -> guidanceResponse(sessions.guide(new RunId(runId), user.userId(), request.content())));
    }

    /** 规格 §39.2 的 GET：某个属主 Run 的持久化工具执行，附带未决审批状态。 */
    @GetMapping("/runs/{runId}/tool-executions")
    public Mono<List<ToolExecutionResponse>> toolExecutions(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @PathVariable String runId) {
        return blocking(() -> toolApprovals.listForRun(new RunId(runId), user.userId()).stream()
                .map(execution -> {
                    var approval = toolApprovals.approvalOf(execution.id());
                    return new ToolExecutionResponse(execution.id(), execution.toolName(),
                            execution.state().name(), execution.inputJson(),
                            approval.map(item -> item.decision().name()).orElse(null),
                            execution.createdAt(), execution.updatedAt());
                }).toList());
    }

    /**
     * 规格 §39.2 的 POST：记录属主的决定。重复决定是幂等空操作（§84），
     * 以 {@code decided=false} 返回，绝不产生错误风暴。
     */
    @PostMapping("/runs/{runId}/tool-executions/{toolExecutionId}/decision")
    public Mono<ToolDecisionResponse> decideToolExecution(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @PathVariable String runId,
                                                          @PathVariable String toolExecutionId,
                                                          @Valid @RequestBody ToolDecisionRequest request) {
        return blocking(() -> new ToolDecisionResponse(toolExecutionId,
                toolApprovals.decide(new RunId(runId), toolExecutionId, user.userId(),
                        request.approve(), request.reason())));
    }

    private static SessionResponse response(AgentSession session) {
        return new SessionResponse(session.id().value(), session.employeeId(), session.status().name(), session.createdAt(), session.lastActiveAt());
    }
    private static RunResponse runResponse(AgentRun run, int queuePosition) {
        return new RunResponse(run.id().value(), run.sessionId().value(), run.state().name(), run.definitionVersionId(), run.createdAt(), queuePosition);
    }
    private static GuidanceResponse guidanceResponse(RunGuidance guidance) { return new GuidanceResponse(guidance.id(), guidance.runId().value(), guidance.status().name(), guidance.createdAt()); }
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateSessionRequest(@Positive long employeeId) { }
    public record CreateRunRequest(@NotBlank @Size(max = 128) String clientRequestId, @NotBlank @Size(max = 4000) String input) { }
    public record SessionResponse(String sessionId, long employeeId, String status, Instant createdAt, Instant lastActiveAt) { }
    public record RunResponse(String runId, String sessionId, String state, long definitionVersionId, Instant createdAt, int queuePosition) { }
    public record GuidanceRequest(@NotBlank @Size(max = 4000) String content) { }
    public record GuidanceResponse(String guidanceId, String runId, String status, Instant createdAt) { }
    public record ToolExecutionResponse(String toolExecutionId, String toolName, String state, String inputJson,
                                        String approvalDecision, Instant createdAt, Instant updatedAt) { }
    public record ToolDecisionRequest(boolean approve, @Size(max = 500) String reason) { }
    public record ToolDecisionResponse(String toolExecutionId, boolean decided) { }
}
