package com.haizhuo.brain.runtime.api;

import com.haizhuo.brain.kernel.identity.RunId;
import java.util.List;

/** Durable control channel read by a runtime only at framework-defined safe checkpoints. */
public interface RunControlInbox {
    List<RunGuidanceMessage> consumeGuidance(RunId runId);

    boolean isCancellationRequested(RunId runId);
}
