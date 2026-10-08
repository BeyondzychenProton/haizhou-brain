package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.util.Objects;

/** Application boundary for explicit, audited termination after the old execution is stopped. */
public class RunRecoveryAdministrationService {
    private final RunRecoveryStore recovery;

    public RunRecoveryAdministrationService(RunRecoveryStore recovery) {
        this.recovery = Objects.requireNonNull(recovery);
    }

    public AgentRun terminate(RunId runId, UserId actor, long expectedFenceToken, String requestId,
                              String stopEvidenceReference, String reason) {
        if (expectedFenceToken <= 0) throw new IllegalArgumentException("expectedFenceToken must be positive");
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        if (stopEvidenceReference == null || stopEvidenceReference.isBlank())
            throw new IllegalArgumentException("stopEvidenceReference is required");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        return recovery.terminate(runId, actor, expectedFenceToken, requestId, stopEvidenceReference, reason);
    }
}
