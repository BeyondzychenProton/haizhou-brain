package com.haizhuo.brain.runtime.api;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;

/** 可信且持久化的引导消息，只可影响某个 Run 的下一步模型推理。 */
public record RunGuidanceMessage(String guidanceId, RunId runId, String source, String content, Instant createdAt) { }
