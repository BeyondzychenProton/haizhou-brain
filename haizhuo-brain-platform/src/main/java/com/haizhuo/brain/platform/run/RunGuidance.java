package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;

/** 面向正在执行中的 Run 的可信控制消息。 */
public record RunGuidance(String id, RunId runId, UserId authorUserId, String source, String content,
                          Status status, Instant createdAt, Instant consumedAt) {
    public enum Status { PENDING, CONSUMED }
}
