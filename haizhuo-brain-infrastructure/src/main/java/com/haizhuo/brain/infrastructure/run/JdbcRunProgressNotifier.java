package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.run.DurableEventMetadata;
import com.haizhuo.brain.platform.run.EventVisibility;
import java.time.Instant;
import java.util.Map;

/** 在调用方业务事务中追加通用、幂等的失效通知事件。 */
final class JdbcRunProgressNotifier {
    private JdbcRunProgressNotifier() { }

    static void append(JdbcRunEventAppender events, RunId runId, String attemptId, long fenceToken,
                       String factKey, Instant occurredAt) {
        DurableEventMetadata metadata = new DurableEventMetadata(2, null, attemptId, fenceToken,
                "ROOT", null, Map.of("runId", runId.value(), "projectionKind", "COLLABORATION",
                "schemaVersion", 1), null, null);
        events.append(runId, "RUN_PROGRESS_UPDATED", "协作进度已更新", EventVisibility.USER, metadata,
                "run-progress:" + factKey, occurredAt);
    }
}
