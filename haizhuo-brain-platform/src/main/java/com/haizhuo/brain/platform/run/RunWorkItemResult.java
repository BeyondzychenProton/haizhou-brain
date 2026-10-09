package com.haizhuo.brain.platform.run;

import java.time.Instant;

/** 某个明确对用户可见的工作项修订对应的完整正文。 */
public record RunWorkItemResult(String runId, String workItemRef, int assignmentRevision,
                                String mediaType, String body, String bodySha256,
                                long byteSize, Instant createdAt) { }
