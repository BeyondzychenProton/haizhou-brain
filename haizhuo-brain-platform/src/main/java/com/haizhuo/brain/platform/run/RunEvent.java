package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;

/** Persisted user-visible event; sequence is the reconnect cursor. */
public record RunEvent(RunId runId, int sequenceNo, String type, String content, Instant createdAt) { }
