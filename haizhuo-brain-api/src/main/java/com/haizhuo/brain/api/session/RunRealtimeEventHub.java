package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.run.RunRealtimeEvent;
import com.haizhuo.brain.platform.run.RunRealtimeEventPublisher;
import java.time.Instant;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 单实例内的瞬时 Run 事件扇出。慢订阅者可以丢失 delta，但持久事件始终可由数据库补读；
 * 多实例共享通知属于后续阶段，不在此内存通道中伪装实现。
 */
@Component
public class RunRealtimeEventHub implements RunRealtimeEventPublisher {
    private static final String TEXT_DELTA = "message.text.delta";

    private final Sinks.Many<RunRealtimeEvent> sink = Sinks.many().multicast().directBestEffort();

    @Override
    public synchronized void publishTextDelta(SessionId sessionId, RunId runId, String attemptId, long streamOffset,
                                              String text, Instant occurredAt) {
        if (text == null || text.isEmpty()) {
            return;
        }
        sink.tryEmitNext(new RunRealtimeEvent(sessionId, runId, attemptId, streamOffset, TEXT_DELTA, text,
                runId.value() + "-assistant", "text", occurredAt));
    }

    public Flux<RunRealtimeEvent> stream(RunId runId) {
        return sink.asFlux()
                .filter(event -> event.runId().equals(runId))
                .publishOn(Schedulers.parallel(), 64);
    }

    /** 会话级订阅：该 Session 下所有 Run 的瞬时增量（P2）。 */
    public Flux<RunRealtimeEvent> streamBySession(SessionId sessionId) {
        return sink.asFlux()
                .filter(event -> event.sessionId().equals(sessionId))
                .publishOn(Schedulers.parallel(), 64);
    }
}
