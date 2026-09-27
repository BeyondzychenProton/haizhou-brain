package com.haizhuo.brain.runtime.api;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;

/** Trusted, durable guidance that may influence only the next model reasoning step of one Run. */
public record RunGuidanceMessage(String guidanceId, RunId runId, String source, String content, Instant createdAt) { }
