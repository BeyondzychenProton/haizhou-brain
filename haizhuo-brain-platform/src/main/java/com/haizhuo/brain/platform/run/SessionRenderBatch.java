package com.haizhuo.brain.platform.run;

import java.time.Instant;

public record SessionRenderBatch(long renderCursor, String runId, String attemptId, long streamOffset,
                                 String messageId, String blockId, long fromOffset, long toOffset,
                                 String delta, Instant occurredAt) { }
