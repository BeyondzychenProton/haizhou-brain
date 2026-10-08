package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;

/** Session 时间线里一条用户可见的不可变事件。 */
public record SessionTimelineItem(RunId runId, int sequenceNo, String type, String content, Instant createdAt,
                                 String executorRoleId) {
    public SessionTimelineItem(RunId runId, int sequenceNo, String type, String content, Instant createdAt) {
        this(runId, sequenceNo, type, content, createdAt, "coordinator");
    }
}
