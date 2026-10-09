package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;

/** 内部成果物索引记录；blobRef 仅供服务端使用，绝不能越过 API 边界。 */
public record RunArtifact(String artifactId, RunId runId, String resultId, UserId ownerUserId,
                          String requestId, String requestDigest, String title, String mediaType,
                          long byteSize, String sha256, String blobRef, State state, Instant createdAt) {
    public enum State { STAGED, AVAILABLE, UNAVAILABLE }

    public RunArtifact {
        if (artifactId == null || artifactId.isBlank() || runId == null || ownerUserId == null
                || requestId == null || requestId.isBlank() || requestDigest == null || requestDigest.isBlank()
                || title == null || mediaType == null || byteSize < 0 || sha256 == null || blobRef == null
                || state == null || createdAt == null) {
            throw new IllegalArgumentException("artifact fields are required");
        }
    }
}
