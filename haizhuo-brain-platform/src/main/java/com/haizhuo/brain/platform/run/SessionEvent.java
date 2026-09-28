package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import java.time.Instant;

/**
 * 会话级持久事件投影（P2）：与 run 事件在同一事务内写入，{@code sessionCursor}
 * 在同一 Session 内严格单调递增，是会话流与历史分页的唯一续传游标。
 * run 内的 {@code runSequence} 仍保留，用于回溯该事件在原 Run 中的位置。
 */
public record SessionEvent(SessionId sessionId, long sessionCursor, RunId runId, Integer runSequence,
                           String type, EventVisibility visibility, String content, Instant createdAt) {
}
