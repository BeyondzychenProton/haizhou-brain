package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.session.AgentSession;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunEvent;
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

/** Session ownership is derived exclusively from the authenticated principal. */
@RestController
@Validated
@RequestMapping("/api/v1/sessions")
public class SessionController {
    private final SessionApplicationService sessions;

    public SessionController(SessionApplicationService sessions) { this.sessions = sessions; }

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

    @PostMapping("/{sessionId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RunResponse> createRun(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String sessionId,
                                       @Valid @RequestBody CreateRunRequest request) {
        return blocking(() -> runResponse(sessions.createRun(new SessionId(sessionId), user.userId(), request.clientRequestId(), request.input().trim())));
    }

    @GetMapping("/runs/{runId}")
    public Mono<RunResponse> getRun(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String runId) {
        return blocking(() -> runResponse(sessions.getRun(new RunId(runId), user.userId())));
    }

    @GetMapping("/runs/{runId}/events")
    public Mono<java.util.List<RunEvent>> events(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String runId, @RequestParam(defaultValue = "0") int after) {
        return blocking(() -> sessions.events(new RunId(runId), user.userId(), after));
    }

    private static SessionResponse response(AgentSession session) {
        return new SessionResponse(session.id().value(), session.employeeId(), session.status().name(), session.createdAt(), session.lastActiveAt());
    }
    private static RunResponse runResponse(AgentRun run) {
        return new RunResponse(run.id().value(), run.sessionId().value(), run.state().name(), run.definitionVersionId(), run.createdAt());
    }
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateSessionRequest(@Positive long employeeId) { }
    public record CreateRunRequest(@NotBlank @Size(max = 128) String clientRequestId, @NotBlank @Size(max = 4000) String input) { }
    public record SessionResponse(String sessionId, long employeeId, String status, Instant createdAt, Instant lastActiveAt) { }
    public record RunResponse(String runId, String sessionId, String state, long definitionVersionId, Instant createdAt) { }
}
