package com.haizhuo.brain.platform.session;

import java.time.Instant;
import java.util.List;

/** 对属主可见的历史 Session、Run 和完整结果执行只读 keyset 查询。 */
public interface SessionHistoryQuery {
    boolean ownsSession(long ownerUserId, String sessionId);

    List<SessionItem> findSessions(SessionFilter filter, Position before, int limit);

    List<RunItem> findRuns(RunFilter filter, Position before, int limit);

    List<ResultItem> findResults(ResultFilter filter, Position before, int limit);

    record SessionFilter(long ownerUserId, Long employeeId, String status) { }

    record RunFilter(long ownerUserId, String sessionId, String state) { }

    record ResultFilter(long ownerUserId, String sessionId) { }

    record Position(Instant createdAt, String id) { }

    record SessionItem(String sessionId, long employeeId, Long definitionVersionId, String status,
                       Instant createdAt, Instant lastActiveAt) { }

    record RunItem(String runId, String sessionId, String state, long definitionVersionId,
                   Instant createdAt, int queuePosition, String executorRoleId, long executorEmployeeId,
                   long executorDefinitionVersionId, String mode) { }

    record ResultItem(String resultId, String runId, String kind, String mediaType, String bodySha256,
                      long byteSize, Instant createdAt, String executorRoleId, Long executorEmployeeId,
                      Long executorDefinitionVersionId) { }

    record Page<T>(List<T> items, String nextCursor, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }
}
