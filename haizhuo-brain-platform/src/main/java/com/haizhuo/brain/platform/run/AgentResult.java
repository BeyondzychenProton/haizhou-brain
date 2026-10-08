package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;
import java.util.Map;

/** 不可覆盖的完整结果；摘要历史不能伪装成完整正文。 */
public record AgentResult(String resultId, RunId runId, String kind, String mediaType, String body,
                          String bodySha256, long byteSize, int schemaVersion, EventVisibility visibility,
                          String attemptId, String invocationId, Map<String, Object> checkMetadata,
                          Instant createdAt, boolean legacySummary, String executorRoleId,
                          Long executorEmployeeId, Long executorDefinitionVersionId) {
    public AgentResult(String resultId, RunId runId, String kind, String mediaType, String body,
                       String bodySha256, long byteSize, int schemaVersion, EventVisibility visibility,
                       String attemptId, String invocationId, Map<String, Object> checkMetadata,
                       Instant createdAt, boolean legacySummary) {
        this(resultId, runId, kind, mediaType, body, bodySha256, byteSize, schemaVersion, visibility,
                attemptId, invocationId, checkMetadata, createdAt, legacySummary, null, null, null);
    }

    public AgentResult { checkMetadata = Map.copyOf(checkMetadata); }
}
