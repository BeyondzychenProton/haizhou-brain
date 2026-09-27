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

    List<RunEvent> findEvents(RunId runId, UserId owner, int afterSequence, int limit);

    Optional<ClaimedRun> claimNextQueuedRun();
    void complete(RunId runId, String result);
    void fail(RunId runId, String reason);
}
