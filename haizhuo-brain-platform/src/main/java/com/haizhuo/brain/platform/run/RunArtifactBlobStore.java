package com.haizhuo.brain.platform.run;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;

/** 私有不可变字节存储；调用方只能传入服务端生成的成果物 ID，不能传文件系统路径。 */
public interface RunArtifactBlobStore {
    void write(String artifactId, byte[] content) throws IOException;
    Optional<byte[]> read(String artifactId) throws IOException;

    /**
     * 仅当调用方确认精确引用不存在时，才删除过期的私有 Blob。
     * 不支持私有目录维护的实现必须按 fail-closed 方式拒绝操作。
     */
    default CleanupBatch cleanupExpired(Instant cutoff, int maxScanned, int maxCandidates,
                                        Duration maxElapsed, Predicate<String> isBlobRefReferenced)
            throws IOException {
        return CleanupBatch.unsupported();
    }

    record CleanupBatch(boolean supported, int scanned, int candidates, int deleted,
                        int retained, boolean timeLimitReached) {
        public static CleanupBatch unsupported() {
            return new CleanupBatch(false, 0, 0, 0, 0, false);
        }
    }
}
