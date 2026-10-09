package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.platform.run.RunArtifactBlobStore;
import java.io.IOException;
import java.util.Optional;

/** 未配置服务端私有存储目录时按 fail-closed 方式拒绝操作。 */
public final class UnavailableRunArtifactBlobStore implements RunArtifactBlobStore {
    @Override public void write(String artifactId, byte[] content) throws IOException {
        throw new IOException("Private artifact storage is not configured");
    }

    @Override public Optional<byte[]> read(String artifactId) throws IOException {
        throw new IOException("Private artifact storage is not configured");
    }
}
