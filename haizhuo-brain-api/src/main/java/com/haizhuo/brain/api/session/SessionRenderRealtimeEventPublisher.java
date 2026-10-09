package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.run.RunRealtimeEventPublisher;
import com.haizhuo.brain.platform.run.SessionRenderDelta;
import com.haizhuo.brain.platform.run.SessionRenderStore;
import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import java.time.Instant;
import java.util.Objects;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 将旧的尽力推送流与独立持久化的 v3 展示投影组合起来。 */
@Component
@Primary
public final class SessionRenderRealtimeEventPublisher implements RunRealtimeEventPublisher {
    private final RunRealtimeEventHub legacyHub;
    private final SessionRenderStore renderStore;

    public SessionRenderRealtimeEventPublisher(RunRealtimeEventHub legacyHub, SessionRenderStore renderStore) {
        this.legacyHub = Objects.requireNonNull(legacyHub);
        this.renderStore = Objects.requireNonNull(renderStore);
    }

    @Override
    public void publishTextDelta(SessionId sessionId, RunId runId, String attemptId, long streamOffset,
                                 String text, Instant occurredAt) {
        legacyHub.publishTextDelta(sessionId, runId, attemptId, streamOffset, text, occurredAt);
    }

    @Override
    public void publishRootTextDelta(SessionId sessionId, RunId runId, String attemptId,
                                     AgentEventDescriptor descriptor, long streamOffset,
                                     long fromOffset, long toOffset, String sanitizedText, Instant occurredAt) {
        if (descriptor == null || descriptor.executionRole() !=
                com.haizhuo.brain.runtime.api.event.AgentExecutionRole.ROOT) return;
        try {
            renderStore.append(new SessionRenderDelta(sessionId, runId, attemptId, descriptor.fenceToken(),
                    descriptor.replyId(), descriptor.blockId(),
                    descriptor.replyId() == null || descriptor.blockId() == null ? "FALLBACK" : "NATIVE",
                    "ROOT", streamOffset, fromOffset, toOffset, sanitizedText, occurredAt));
        } catch (RuntimeException failure) {
            try { renderStore.markDegraded(sessionId, runId, attemptId, descriptor.fenceToken(),
                    "WRITE_FAILED", occurredAt); } catch (RuntimeException ignored) { }
        }
    }
}
