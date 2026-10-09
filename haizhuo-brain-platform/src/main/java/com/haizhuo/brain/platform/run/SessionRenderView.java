package com.haizhuo.brain.platform.run;

import java.util.List;

public record SessionRenderView(String sessionId, List<SessionRenderRun> runs,
                                List<SessionRenderMessage> items, List<ResultReference> resultRefs,
                                long snapshotCursor, long cursorFloor, long renderCursor,
                                long renderCursorFloor, String draftRecoveryStatus,
                                String nextCursor, boolean hasMore) {
    public SessionRenderView {
        runs = List.copyOf(runs);
        items = List.copyOf(items);
        resultRefs = List.copyOf(resultRefs);
    }
    public record ResultReference(String resultId, String runId, String kind, String mediaType,
                                  String bodySha256, long byteSize, String executorRoleId) { }
}
