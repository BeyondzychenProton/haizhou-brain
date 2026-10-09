package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import java.time.Instant;

/** 从已运行的 AgentScope 订阅中观察到的已脱敏根文字区间。 */
public record SessionRenderDelta(SessionId sessionId, RunId runId, String attemptId, Long fenceToken,
                                 String replyId, String blockId, String identityQuality,
                                 String executorRoleId, long streamOffset, long fromOffset, long toOffset, String text,
                                 Instant occurredAt) {
    public SessionRenderDelta {
        if (sessionId == null || runId == null || attemptId == null || attemptId.isBlank()
                || fenceToken == null || streamOffset < 1 || fromOffset < 0 || toOffset <= fromOffset || text == null
                || text.isEmpty() || occurredAt == null) throw new IllegalArgumentException("Invalid render delta");
        if (!"ROOT".equals(executorRoleId)) throw new IllegalArgumentException("Only root text can be rendered");
        if (!"NATIVE".equals(identityQuality) && !"FALLBACK".equals(identityQuality))
            throw new IllegalArgumentException("Invalid render identity quality");
    }
}
