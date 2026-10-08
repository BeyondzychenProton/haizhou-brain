package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;

/** Audited administrative handling for Runs whose external execution outcome is uncertain. */
public interface RunRecoveryStore {
    AgentRun terminate(RunId runId, UserId actor, long expectedFenceToken, String requestId,
                       String stopEvidenceReference, String reason);
}
