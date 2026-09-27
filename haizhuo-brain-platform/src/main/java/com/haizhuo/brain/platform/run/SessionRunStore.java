package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.session.AgentSession;
import java.util.Optional;
import java.util.List;

/** Transactional persistence boundary. Every lookup accepts the authenticated owner. */
public interface SessionRunStore {
    AgentSession createSession(AgentSession session);

    Optional<AgentSession> findSession(SessionId sessionId, UserId owner);
    default List<AgentSession> findSessions(UserId owner, int limit) { return List.of(); }

    /** Atomically claims the only active Run slot for the owned Session, or returns an idempotent replay. */
    AgentRun createRun(AgentRun run, String input);

    Optional<AgentRun> findRun(RunId runId, UserId owner);
    default List<AgentRun> findRuns(SessionId sessionId, UserId owner, int limit) { return List.of(); }

    List<RunEvent> findEvents(RunId runId, UserId owner, int afterSequence, int limit);

    default List<SessionTimelineItem> findTimeline(SessionId sessionId, UserId owner, int limit) { return List.of(); }

    default int queuePosition(RunId runId, UserId owner) { return 0; }

    default AgentRun cancel(RunId runId, UserId owner) { throw new UnsupportedOperationException("Run cancellation is not available"); }

    default RunGuidance addGuidance(RunId runId, UserId owner, String source, String content) {
        throw new UnsupportedOperationException("Run guidance is not available");
    }

    Optional<ClaimedRun> claimNextQueuedRun();
    void complete(RunId runId, String result);
    void fail(RunId runId, String reason);
    default void cancelled(RunId runId, String reason) { fail(runId, reason); }
}
