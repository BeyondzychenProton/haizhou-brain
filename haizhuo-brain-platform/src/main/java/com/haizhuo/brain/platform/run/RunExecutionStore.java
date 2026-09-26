package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import java.util.Optional;

/** Durable Session/Run boundary; an implementation freezes the published definition at claim time. */
public interface RunExecutionStore {
    Optional<AgentExecutionRequest> claimNextRun();

    /** Persists user-visible progress before a Web subscriber can observe it. */
    void appendWorkEvent(RunId runId, String kind, String content);

    /** Saves the result and outbound intent, releases the Session, then queues its next inbox item. */
    void completeRun(RunId runId, String result);

    /** Saves failure and releases or quarantines the Session according to recovery policy. */
    void failRun(RunId runId, String reason);
}
