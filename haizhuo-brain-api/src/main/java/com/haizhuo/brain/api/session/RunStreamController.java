package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.RunRealtimeEvent;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/** Run 级持久补读与瞬时文本增量流；SSE 断开不会改变 Run 状态。 */
@RestController
@RequestMapping("/api/v1/sessions/runs")
public class RunStreamController {
    private static final int BATCH_SIZE = 200;
    private static final Duration DATABASE_POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    /** 单次补读失败后最多重试的次数（共 1 + 本值 次尝试）；耗尽后整条流按错误结束，由浏览器重连继续。 */
    private static final int DATABASE_MAX_RETRIES = 2;
    private static final Duration DATABASE_RETRY_MIN_BACKOFF = Duration.ofMillis(200);
    private static final Duration DATABASE_RETRY_MAX_BACKOFF = Duration.ofSeconds(2);

    private final SessionApplicationService sessions;
    private final RunRealtimeEventHub realtimeEvents;

    public RunStreamController(SessionApplicationService sessions, RunRealtimeEventHub realtimeEvents) {
        this.sessions = sessions;
        this.realtimeEvents = realtimeEvents;
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> stream(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String runId,
            @RequestParam(required = false) Integer after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noCache());
        exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");

        RunId id = new RunId(runId);
        int resumeAfter = Math.max(Math.max(after == null ? 0 : after, 0), parseLastEventId(lastEventId));
        return blocking(() -> sessions.getRun(id, user.userId()))
                .flatMapMany(run -> eventStream(run, user, resumeAfter));
    }

    private Flux<ServerSentEvent<StreamEvent>> eventStream(AgentRun run, AuthenticatedUser user, int resumeAfter) {
        RunId runId = run.id();
        AtomicInteger cursor = new AtomicInteger(resumeAfter);
        Flux<ServerSentEvent<StreamEvent>> durable = Flux
                .interval(Duration.ZERO, DATABASE_POLL_INTERVAL)
                // 上一次补读尚未结束（含重试退避）时丢弃本次 tick：interval 无法缓冲，
                // 否则会以 OverflowException 终止整条流，慢数据库反而比断线更致命。
                .onBackpressureDrop()
                .concatMap(ignored -> blocking(() -> sessions.eventsOfOwnedRun(
                                runId, user.userId(), cursor.get(), BATCH_SIZE))
                        // 数据库抖动不应终止整条 SSE；重试耗尽后仍失败才把错误交给浏览器，
                        // 届时前端按退避重连并在补读窗口内自愈。
                        .retryWhen(Retry.backoff(DATABASE_MAX_RETRIES, DATABASE_RETRY_MIN_BACKOFF)
                                .maxBackoff(DATABASE_RETRY_MAX_BACKOFF)))
                .flatMapIterable(events -> events)
                .filter(event -> event.sequenceNo() > cursor.get())
                .doOnNext(event -> cursor.accumulateAndGet(event.sequenceNo(), Math::max))
                .map(event -> durableEvent(run.sessionId().value(), event));

        Flux<ServerSentEvent<StreamEvent>> transientEvents = realtimeEvents.stream(runId)
                .map(RunStreamController::transientEvent);

        Flux<ServerSentEvent<StreamEvent>> heartbeats = Flux.interval(HEARTBEAT_INTERVAL)
                .map(ignored -> ServerSentEvent.<StreamEvent>builder()
                        .comment("keepalive")
                        .build());

        return Flux.merge(durable, transientEvents, heartbeats)
                .takeUntil(event -> event.data() != null && isTerminal(event.data().type()));
    }

    static ServerSentEvent<StreamEvent> durableEvent(String sessionId, RunEvent event) {
        String messageId = switch (event.type()) {
            case "USER_INPUT" -> event.runId().value() + "-user-" + event.sequenceNo();
            case "RUN_COMPLETED" -> event.runId().value() + "-assistant";
            default -> null;
        };
        StreamEvent response = new StreamEvent(1,
                event.runId().value() + ":" + event.sequenceNo(), sessionId, null, event.runId().value(),
                event.sequenceNo(), null, null, event.type(), EventVisibility.USER.name(), "durable",
                event.createdAt(),
                new StreamEvent.Payload(messageId, messageId == null ? null : "text", null, event.content()));
        return ServerSentEvent.<StreamEvent>builder(response)
                .id(Integer.toString(event.sequenceNo()))
                .build();
    }

    static ServerSentEvent<StreamEvent> transientEvent(RunRealtimeEvent event) {
        StreamEvent response = new StreamEvent(1,
                event.runId().value() + ":" + event.attemptId() + ":stream:" + event.streamOffset(),
                event.sessionId().value(), null, event.runId().value(), null, event.attemptId(),
                event.streamOffset(), event.type(), EventVisibility.USER.name(), "transient",
                event.occurredAt(),
                new StreamEvent.Payload(event.messageId(), event.blockId(), event.content(), null));
        return ServerSentEvent.builder(response).build();
    }

    static boolean isTerminal(String type) {
        return "RUN_COMPLETED".equals(type) || "RUN_FAILED".equals(type)
                || "RUN_CANCELLED".equals(type);
    }

    private static int parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(lastEventId), 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}
