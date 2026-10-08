package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;

/** 持久化的用户可见事件；sequence 即重连游标。 */
public record RunEvent(RunId runId, int sequenceNo, String type, String content, Instant createdAt,
                       EventVisibility visibility, DurableEventMetadata metadata) {
    public RunEvent(RunId runId, int sequenceNo, String type, String content, Instant createdAt) {
        this(runId, sequenceNo, type, content, createdAt, EventVisibility.USER, DurableEventMetadata.legacy());
    }
}
