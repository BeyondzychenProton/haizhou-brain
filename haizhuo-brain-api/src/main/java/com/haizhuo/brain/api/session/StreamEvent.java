package com.haizhuo.brain.api.session;

import java.time.Instant;
import java.util.Map;

/**
 * 跨渠道统一事件信封（改造方案 §5）。持久事件的 {@code sessionCursor} 与 {@code runSequence}
 * 才是可重放游标；瞬时 {@code message.text.delta} 只带 {@code attemptId + streamOffset}，
 * 两个持久序号为 null，客户端不得把它当作可按游标重放的事件。
 */
public record StreamEvent(int schemaVersion, String eventId, String sessionId, Long sessionCursor, String runId,
                          Integer runSequence, String attemptId, Long streamOffset, String type, String visibility,
                          String durability, Instant occurredAt, Payload payload) {

    public record Payload(String messageId, String blockId, String delta, String text,
                          Map<String, Object> metadata) {
        public Payload(String messageId, String blockId, String delta, String text) {
            this(messageId, blockId, delta, text, Map.of());
        }

        public Payload {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
