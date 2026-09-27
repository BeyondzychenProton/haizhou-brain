package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.session.AgentSession;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    private static SessionResponse response(AgentSession session) {
        return new SessionResponse(session.id().value(), session.employeeId(), session.status().name(), session.createdAt(), session.lastActiveAt());
    }
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateSessionRequest(@Positive long employeeId) { }
    public record SessionResponse(String sessionId, long employeeId, String status, Instant createdAt, Instant lastActiveAt) { }
}
