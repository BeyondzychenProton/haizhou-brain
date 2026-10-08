package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.util.List;
import java.util.Optional;

/** 用户读面必须同时核验 Run 属主和结果可见性。 */
public interface AgentResultRepository {
    Optional<AgentResult> findRoot(RunId runId, UserId owner);
    Optional<AgentResult> findOwned(String resultId, RunId runId, UserId owner);

    /** Only complete USER-visible results from the requested owned Session may be explicitly shared. */
    default Optional<AgentResult> findReferenceable(String resultId, SessionId sessionId, UserId owner) {
        return Optional.empty();
    }

    default List<AgentResult> listReferenceable(SessionId sessionId, UserId owner, int limit) { return List.of(); }

    /** Immutable references accepted with a Run, loaded again by its Worker after restart. */
    default List<AgentResult> findReferencesForRun(RunId runId, UserId owner) { return List.of(); }
}
