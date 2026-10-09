package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;

/** 不可变的用户反馈事实；导出状态不代表远端已经持久化。 */
public record RunFeedback(String feedbackId, RunId runId, UserId userId, String clientRequestId,
                          String requestDigest, double value, String comment, Instant createdAt,
                          ExportDisposition exportDisposition) {
    public RunFeedback {
        if (feedbackId == null || feedbackId.isBlank() || runId == null || userId == null
                || clientRequestId == null || clientRequestId.isBlank() || requestDigest == null
                || requestDigest.isBlank() || !Double.isFinite(value) || value < 0 || value > 1
                || createdAt == null || exportDisposition == null)
            throw new IllegalArgumentException("feedback fields are invalid");
        if (comment != null && comment.length() > 1000) throw new IllegalArgumentException("comment is too long");
    }

    public enum ExportDisposition { QUEUE_STATUS_UNKNOWN, ACCEPTED_NOT_CONFIRMED, NOT_ACCEPTED }
}
