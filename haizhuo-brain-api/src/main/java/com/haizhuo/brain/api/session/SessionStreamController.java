package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.SessionEvent;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 会话级补读与订阅（P2）：跨 Run 保持同一条流，SSE id 只绑持久 sessionCursor。
 * 与 Run 级流不同，会话流不因某个 Run 终态而结束——后续 Run 仍在同一条连接上推送。
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionStreamController {
    private static final int BATCH_SIZE = 200;
    private static final Duration DATABASE_POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final int DATABASE_MAX_RETRIES = 2;
    private static final Duration DATABASE_RETRY_MIN_BACKOFF = Duration.ofMillis(200);
    private static final Duration DATABASE_RETRY_MAX_BACKOFF = Duration.ofSeconds(2);

    private final SessionApplicationService sessions;
    private final RunRealtimeEventHub realtimeEvents;

    public SessionStreamController(SessionApplicationService sessions, RunRealtimeEventHub realtimeEvents) {
        this.sessions = sessions;
        this.realtimeEvents = realtimeEvents;
    }

    @GetMapping("/{sessionId}/events")
    public Mono<List<StreamEvent>> events(@AuthenticationPrincipal AuthenticatedUser user,
                                          @PathVariable String sessionId,
                                          @RequestParam(defaultValue = "0") long after,
                                          @RequestParam(defaultValue = "200") int limit) {
        return RunStreamController.blocking(() -> sessions
                .sessionEvents(new SessionId(sessionId), user.userId(), after, limit).stream()
                .map(SessionStreamController::envelope)
                .toList());
    }

    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> stream(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String sessionId,
            @RequestParam(required = false) Long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noCache());
        exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");

        SessionId id = new SessionId(sessionId);
        long resumeAfter = Math.max(after == null ? 0 : after, parseLastEventId(lastEventId));
        return RunStreamController.blocking(() -> sessions.get(id, user.userId()))
                .flatMapMany(session -> eventStream(id, user, resumeAfter));
    }

    private Flux<ServerSentEvent<StreamEvent>> eventStream(SessionId sessionId, AuthenticatedUser user,
                                                           long resumeAfter) {
        AtomicLong cursor = new AtomicLong(resumeAfter);
        Flux<ServerSentEvent<StreamEvent>> durable = Flux
                .interval(Duration.ZERO, DATABASE_POLL_INTERVAL)
                .onBackpressureDrop()
                .concatMap(ignored -> RunStreamController.blocking(() -> sessions.sessionEventsOfOwnedSession(
                                sessionId, user.userId(), cursor.get(), BATCH_SIZE))
                        .retryWhen(Retry.backoff(DATABASE_MAX_RETRIES, DATABASE_RETRY_MIN_BACKOFF)
                                .maxBackoff(DATABASE_RETRY_MAX_BACKOFF)))
                .flatMapIterable(events -> events)
                .filter(event -> event.sessionCursor() > cursor.get())
                .doOnNext(event -> cursor.accumulateAndGet(event.sessionCursor(), Math::max))
                .map(SessionStreamController::durableEvent);

        Flux<ServerSentEvent<StreamEvent>> transientEvents = realtimeEvents.streamBySession(sessionId)
                .map(RunStreamController::transientEvent);

        Flux<ServerSentEvent<StreamEvent>> heartbeats = Flux.interval(HEARTBEAT_INTERVAL)
                .map(ignored -> ServerSentEvent.<StreamEvent>builder()
                        .comment("keepalive")
                        .build());

        // 会话流跨 Run 保持开放，因此不按某个 Run 的终态结束；断线由客户端退避重连并按游标补读。
        return Flux.merge(durable, transientEvents, heartbeats);
    }

    static StreamEvent envelope(SessionEvent event) {
        return new StreamEvent(1,
                event.sessionId().value() + ":" + event.sessionCursor(), event.sessionId().value(),
                event.sessionCursor(), event.runId() == null ? null : event.runId().value(), event.runSequence(),
                null, null, event.type(), visibility(event), "durable", event.createdAt(),
                new StreamEvent.Payload(messageId(event), messageId(event) == null ? null : "text", null,
                        event.content()));
    }

    private static ServerSentEvent<StreamEvent> durableEvent(SessionEvent event) {
        return ServerSentEvent.<StreamEvent>builder(envelope(event))
                .id(Long.toString(event.sessionCursor()))
                .build();
    }

    static String messageId(SessionEvent event) {
        if (event.runId() == null) {
            return null;
        }
        return switch (event.type()) {
            case "USER_INPUT" -> event.runId().value() + "-user-" + event.runSequence();
            case "RUN_COMPLETED" -> event.runId().value() + "-assistant";
            default -> null;
        };
    }

    private static String visibility(SessionEvent event) {
        return (event.visibility() == null ? EventVisibility.USER : event.visibility()).name();
    }

    static long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Long.parseLong(lastEventId), 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

}
