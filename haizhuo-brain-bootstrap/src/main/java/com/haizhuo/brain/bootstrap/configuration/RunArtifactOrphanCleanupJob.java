package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.run.RunArtifactBlobStore;
import com.haizhuo.brain.platform.run.RunArtifactOrphanCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 每小时执行一次有界维护；服务端成果物准入门槛关闭时不运行。 */
public final class RunArtifactOrphanCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(RunArtifactOrphanCleanupJob.class);
    private final RunArtifactOrphanCleanupService cleanup;
    private final MarkdownArtifactAdmission admission;

    RunArtifactOrphanCleanupJob(RunArtifactOrphanCleanupService cleanup, MarkdownArtifactAdmission admission) {
        this.cleanup = cleanup;
        this.admission = admission;
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void clean() {
        if (!admission.enabled()) return;
        try {
            RunArtifactBlobStore.CleanupBatch batch = cleanup.cleanOneBatch();
            if (!batch.supported()) return;
            if (batch.deleted() > 0 || batch.timeLimitReached()) {
                log.info("Private Markdown blob cleanup batch: scanned={}, candidates={}, deleted={}, retained={}, "
                                + "timeLimitReached={}",
                        batch.scanned(), batch.candidates(), batch.deleted(), batch.retained(),
                        batch.timeLimitReached());
            }
        } catch (java.io.IOException | RuntimeException failure) {
            // 常规日志不得记录 Blob 引用、存储路径、SQL 或异常消息。
            log.warn("Private Markdown blob cleanup batch failed: {}", failure.getClass().getSimpleName());
        }
    }
}
