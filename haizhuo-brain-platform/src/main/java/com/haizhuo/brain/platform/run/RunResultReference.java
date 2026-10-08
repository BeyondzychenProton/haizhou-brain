package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;

/** Immutable, user-authorized reference frozen at Run acceptance. */
public record RunResultReference(String resultId, RunId sourceRunId, String bodySha256) {
    public RunResultReference {
        if (resultId == null || resultId.isBlank()) throw new IllegalArgumentException("resultId is required");
        if (sourceRunId == null) throw new IllegalArgumentException("sourceRunId is required");
        if (bodySha256 == null || !bodySha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("A complete result hash is required");
    }
}
