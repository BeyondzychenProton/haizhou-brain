package com.haizhuo.brain.platform.run;

import java.time.Instant;
import java.util.List;

public record SessionRenderMessage(String messageId, String runId, String attemptId, String executorRoleId,
                                   String kind, int kindOrder,
                                   long messageOrdinal, String itemType, String phase, String bodySource,
                                   String text, String resultId, boolean legacySummary, boolean partial,
                                   Instant runCreatedAt, Instant occurredAt, List<Block> blocks) {
    public SessionRenderMessage { blocks = blocks == null ? List.of() : List.copyOf(blocks); }
    public record Block(String blockId, String type, String text, long lastAppliedOffset) { }
}
