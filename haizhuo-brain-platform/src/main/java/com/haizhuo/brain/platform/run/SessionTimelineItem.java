package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;

/** One user-visible, immutable event in a Session timeline. */
public record SessionTimelineItem(RunId runId, int sequenceNo, String type, String content, Instant createdAt) { }
