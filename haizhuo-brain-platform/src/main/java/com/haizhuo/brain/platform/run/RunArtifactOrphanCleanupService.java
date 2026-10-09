package com.haizhuo.brain.platform.run;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** 清理未被引用的不可变 Markdown Blob 时使用的有界策略。 */
public final class RunArtifactOrphanCleanupService {
    public static final Duration RETENTION = Duration.ofDays(7);
    public static final int MAX_SCANNED_PER_BATCH = 1_000;
    public static final int MAX_CANDIDATES_PER_BATCH = 25;
    public static final Duration MAX_BATCH_ELAPSED = Duration.ofSeconds(5);

    private final RunArtifactBlobStore blobs;
    private final RunArtifactBlobReferenceQuery references;
    private final Clock clock;

    public RunArtifactOrphanCleanupService(RunArtifactBlobStore blobs,
                                           RunArtifactBlobReferenceQuery references,
                                           Clock clock) {
        this.blobs = blobs;
        this.references = references;
        this.clock = clock;
    }

    public RunArtifactBlobStore.CleanupBatch cleanOneBatch() throws IOException {
        Instant cutoff = clock.instant().minus(RETENTION);
        return blobs.cleanupExpired(cutoff, MAX_SCANNED_PER_BATCH, MAX_CANDIDATES_PER_BATCH,
                MAX_BATCH_ELAPSED, references::isBlobRefReferenced);
    }
}
