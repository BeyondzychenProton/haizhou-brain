package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.SessionEvent;
import com.haizhuo.brain.platform.run.SessionEventPage;
import com.haizhuo.brain.platform.run.SessionRenderBatch;
import com.haizhuo.brain.platform.run.SessionRenderStore;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 仅通过 JDBC 恢复展示；重连此接口不会重新订阅 AgentRuntime。 */
@RestController
@RequestMapping("/api/v1/sessions")
public final class SessionRenderStreamController {
    private static final int LIMIT = 200;
    private static final Duration POLL = Duration.ofMillis(250);
    private final SessionApplicationService sessions;
    private final SessionRenderStore renderStore;
    private final boolean enabled;

    public SessionRenderStreamController(SessionApplicationService sessions, SessionRenderStore renderStore,
                                         @Value("${haizhuo.brain.session-render-v3.enabled:false}") boolean enabled) {
        this.sessions = sessions;
        this.renderStore = renderStore;
        this.enabled = enabled;
    }

    @GetMapping(value = "/{sessionId}/render-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<SessionRenderEvent>> stream(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String sessionId, @RequestParam(defaultValue = "0") Long after,
            @RequestParam(defaultValue = "0") Long renderAfter,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            ServerWebExchange exchange) {
        if (!enabled) return Flux.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noCache());
        exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");
        SessionId sid = new SessionId(sessionId);
        long startSession = Math.max(Math.max(0L, after == null ? 0L : after), parseCursor(lastEventId));
        long startRender = Math.max(0L, renderAfter == null ? 0L : renderAfter);
        return RunStreamController.blocking(() -> sessions.get(sid, user.userId()))
                .flatMapMany(ignored -> events(sid, user, startSession, startRender));
    }

    private Flux<ServerSentEvent<SessionRenderEvent>> events(SessionId sid, AuthenticatedUser user,
                                                              long startSession, long startRender) {
        AtomicLong sessionCursor = new AtomicLong(startSession);
        AtomicLong renderCursor = new AtomicLong(startRender);
        Flux<ServerSentEvent<SessionRenderEvent>> updates = Flux.interval(Duration.ZERO, POLL).onBackpressureDrop()
                .concatMap(tick -> RunStreamController.blocking(() -> {
                    SessionEventPage facts = sessions.sessionEventPageOfOwnedSession(
                            sid, user.userId(), sessionCursor.get(), LIMIT);
                    SessionRenderStore.RenderBatchPage batches = renderStore.batches(
                            sid, user.userId(), sessionCursor.get(), renderCursor.get(), LIMIT);
                    List<ServerSentEvent<SessionRenderEvent>> outgoing = new ArrayList<>();
                    if (facts.cursorExpired()) {
                        outgoing.add(control(sid, "SESSION_CURSOR_EXPIRED", facts.cursorFloor(), null));
                    } else {
                        for (SessionEvent fact : facts.events()) {
                            if (fact.visibility() != EventVisibility.USER) continue;
                            outgoing.add(durable(fact));
                            sessionCursor.accumulateAndGet(fact.sessionCursor(), Math::max);
                        }
                        // nextCursor 同时计入本次扫描页中被过滤的私有记录。
                        // 游标越过该有限页面后，可避免无限重复读取内部事实。
                        sessionCursor.accumulateAndGet(facts.nextCursor(), Math::max);
                    }
                    if (batches.expired()) {
                        outgoing.add(control(sid, "RENDER_CURSOR_EXPIRED", null, batches.cursorFloor()));
                    } else {
                        for (SessionRenderBatch batch : batches.batches()) {
                            outgoing.add(transientBatch(sid, batch));
                            renderCursor.accumulateAndGet(batch.renderCursor(), Math::max);
                        }
                    }
                    return outgoing;
                })).flatMapIterable(items -> items);
        Flux<ServerSentEvent<SessionRenderEvent>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(ignored -> ServerSentEvent.<SessionRenderEvent>builder().comment("keepalive").build());
        return Flux.merge(updates, heartbeat).takeUntil(item -> item.data() != null
                && ("SESSION_CURSOR_EXPIRED".equals(item.data().type())
                || "RENDER_CURSOR_EXPIRED".equals(item.data().type())));
    }

    private static ServerSentEvent<SessionRenderEvent> durable(SessionEvent event) {
        String messageId = switch (event.type()) {
            case "USER_INPUT" -> event.runId().value() + "-user-" + event.runSequence();
            case "RUN_COMPLETED" -> event.runId().value() + "-assistant";
            default -> null;
        };
        var data = new SessionRenderEvent(3, event.sessionId().value() + ":" + event.sessionCursor(),
                event.sessionId().value(), event.sessionCursor(), null,
                event.runId() == null ? null : event.runId().value(), event.runSequence(), null, null,
                event.type(), "USER", "durable", event.createdAt(),
                new SessionRenderEvent.Payload(messageId, messageId == null ? null : "text", null, null,
                        null, event.content(), event.metadata() == null ? null : event.metadata().resultId(), Map.of()));
        return ServerSentEvent.<SessionRenderEvent>builder(data).id(Long.toString(event.sessionCursor())).build();
    }

    private static ServerSentEvent<SessionRenderEvent> transientBatch(SessionId sid, SessionRenderBatch batch) {
        var data = new SessionRenderEvent(3, sid.value() + ":render:" + batch.renderCursor(), sid.value(), null,
                batch.renderCursor(), batch.runId(), null, batch.attemptId(), batch.streamOffset(),
                "message.text.batch", "USER", "transient", batch.occurredAt(),
                new SessionRenderEvent.Payload(batch.messageId(), batch.blockId(), batch.fromOffset(),
                        batch.toOffset(), batch.delta(), null, null, Map.of()));
        return ServerSentEvent.<SessionRenderEvent>builder(data).build();
    }

    private static ServerSentEvent<SessionRenderEvent> control(SessionId sid, String type,
                                                               Long sessionFloor, Long renderFloor) {
        var data = new SessionRenderEvent(3, sid.value() + ":" + type, sid.value(), null, null,
                null, null, null, null, type, "USER", "control", java.time.Instant.now(),
                new SessionRenderEvent.Payload(null, null, null, null, null, null, null,
                        Map.of("cursorFloor", sessionFloor == null ? renderFloor : sessionFloor)));
        return ServerSentEvent.<SessionRenderEvent>builder(data).build();
    }

    private static long parseCursor(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
