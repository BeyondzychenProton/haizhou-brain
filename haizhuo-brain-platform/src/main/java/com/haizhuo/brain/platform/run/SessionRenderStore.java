package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;
import java.util.List;

/** 仅用于持久化展示投影；绝不能 claim、恢复或执行 Run。 */
public interface SessionRenderStore {
    long append(SessionRenderDelta delta);

    /** 展示写入失败时尽力记录诊断信息，绝不改变 Run 状态。 */
    default void markDegraded(SessionId sessionId, RunId runId, String attemptId, Long fenceToken,
                              String reasonCode, Instant occurredAt) { }

    SessionRenderView view(SessionId sessionId, UserId owner, int limit, HistoryBoundary before);

    RenderBatchPage batches(SessionId sessionId, UserId owner, long afterSessionCursor,
                            long afterRenderCursor, int limit);

    record HistoryBoundary(long snapshotCursor, long renderCursor, Instant runCreatedAt,
                           String runId, int kindOrder, long ordinal, String messageId) { }
    record RenderBatchPage(List<SessionRenderBatch> batches, long cursorFloor,
                           long committedCursor, boolean expired) {
        public RenderBatchPage { batches = List.copyOf(batches); }
    }
}
