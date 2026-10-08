package com.haizhuo.brain.api.session;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.haizhuo.brain.platform.run.DurableEventMetadata;

/**
 * 跨渠道统一事件信封（改造方案 §5）。持久事件的 {@code sessionCursor} 与 {@code runSequence}
 * 才是可重放游标；瞬时 {@code message.text.delta} 只带 {@code attemptId + streamOffset}，
 * 两个持久序号为 null，客户端不得把它当作可按游标重放的事件。
 */
public record StreamEvent(int schemaVersion, String eventId, String sessionId, Long sessionCursor, String runId,
                          Integer runSequence, String attemptId, Long streamOffset, String type, String visibility,
                          String durability, Instant occurredAt, Payload payload,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Origin origin,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String resultId) {
    public StreamEvent(int schemaVersion,String eventId,String sessionId,Long sessionCursor,String runId,
                       Integer runSequence,String attemptId,Long streamOffset,String type,String visibility,
                       String durability,Instant occurredAt,Payload payload) {
        this(schemaVersion,eventId,sessionId,sessionCursor,runId,runSequence,attemptId,streamOffset,type,visibility,durability,occurredAt,payload,null,null);
    }
    /** 原生实例引用与 fence 仅供内部核查，不进入用户事件。 */
    public record Origin(String kind) { }

    static boolean isV2(String format) {
        if (!"v1".equals(format) && !"v2".equals(format))
            throw new IllegalArgumentException("Unsupported event format");
        return "v2".equals(format);
    }

    static Origin origin(DurableEventMetadata metadata, boolean v2) {
        return v2 ? new Origin(metadata == null ? "PLATFORM" : metadata.originKind()) : null;
    }

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
