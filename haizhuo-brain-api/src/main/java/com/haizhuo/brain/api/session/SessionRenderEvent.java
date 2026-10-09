package com.haizhuo.brain.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/** v3 信封分别保存持久 sessionCursor 与临时 renderCursor。 */
public record SessionRenderEvent(int schemaVersion, String eventId, String sessionId,
                                 Long sessionCursor, Long renderCursor, String runId,
                                 Integer runSequence, String attemptId, Long streamOffset,
                                 String type, String visibility, String durability,
                                 Instant occurredAt, Payload payload) {
    public record Payload(String messageId, String blockId, Long fromOffset, Long toOffset,
                          String delta, String text, String resultId,
                          @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata) {
        public Payload { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }
}
