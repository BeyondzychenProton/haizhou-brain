package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.session.AgentSession;
import java.util.Optional;

/** Transactional persistence boundary. Every lookup accepts the authenticated owner. */
public interface SessionRunStore {
    AgentSession createSession(AgentSession session);

    Optional<AgentSession> findSession(SessionId sessionId, UserId owner);

    Optional<AgentRun> findRun(RunId runId, UserId owner);
}
