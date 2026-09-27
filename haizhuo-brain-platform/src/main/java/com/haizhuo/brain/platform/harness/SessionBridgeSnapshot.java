package com.haizhuo.brain.platform.harness;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;
import java.util.Objects;

/**
 * 把用户可见上下文一次性桥接进一个全新的 Harness 会话（规格 §6.5/§17）。
 * 仅在新建 SessionHarnessBinding 时创建——不会每次 Run 重新摘要。
 * 只保存用户可见历史、显式业务事实与产物引用；
 * 凭据、旧计划、工具上下文与运行时记忆一律禁止出现在这里（§17.2）。
 */
public record SessionBridgeSnapshot(long id, UserId userId, SessionId platformSessionId,
                                    long definitionVersionId, int generation, int sourceEventSequence,
                                    String conversationSummary, String stableBusinessFactsJson,
                                    int schemaVersion, String contentHash, Instant createdAt) {
    public SessionBridgeSnapshot {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(platformSessionId);
        if (definitionVersionId <= 0) throw new IllegalArgumentException("definitionVersionId must be positive");
        if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
        if (sourceEventSequence < 0) throw new IllegalArgumentException("sourceEventSequence must be >= 0");
        Objects.requireNonNull(conversationSummary);
        Objects.requireNonNull(stableBusinessFactsJson);
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
        Objects.requireNonNull(contentHash);
        Objects.requireNonNull(createdAt);
    }
}
