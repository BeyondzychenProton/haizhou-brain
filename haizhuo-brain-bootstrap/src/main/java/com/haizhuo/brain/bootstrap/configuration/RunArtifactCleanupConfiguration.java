package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.run.RunArtifactBlobReferenceQuery;
import com.haizhuo.brain.platform.run.RunArtifactBlobStore;
import com.haizhuo.brain.platform.run.RunArtifactOrphanCleanupService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 私有 Markdown Blob 的维护由服务端负责，并使用同一功能准入门槛。 */
@Configuration
public class RunArtifactCleanupConfiguration {
    @Bean
    RunArtifactOrphanCleanupService runArtifactOrphanCleanupService(
            RunArtifactBlobStore blobs, RunArtifactBlobReferenceQuery references, Clock clock) {
        return new RunArtifactOrphanCleanupService(blobs, references, clock);
    }

    @Bean
    RunArtifactOrphanCleanupJob runArtifactOrphanCleanupJob(
            RunArtifactOrphanCleanupService cleanup, MarkdownArtifactAdmission admission) {
        return new RunArtifactOrphanCleanupJob(cleanup, admission);
    }
}
