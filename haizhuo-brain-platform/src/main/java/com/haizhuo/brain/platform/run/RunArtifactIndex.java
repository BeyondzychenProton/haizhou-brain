package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Run 不可变成果物的持久化属主范围元数据索引。 */
public interface RunArtifactIndex {
    StageResult stage(RunArtifact artifact);
    RunArtifact markAvailable(String artifactId, UserId owner, String sha256, long byteSize);
    void markUnavailable(String artifactId, UserId owner);
    Optional<RunArtifact> find(String artifactId, UserId owner);
    Page page(RunId runId, UserId owner, Position before, int limit);

    record StageResult(RunArtifact artifact, boolean created) { }
    record Position(Instant createdAt, String artifactId) { }
    record Page(List<RunArtifact> items, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }
}
