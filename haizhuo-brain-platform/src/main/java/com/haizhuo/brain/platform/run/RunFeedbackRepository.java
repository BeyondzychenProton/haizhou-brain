package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 持久化属主范围内的反馈事实；实现必须原子校验请求键。 */
public interface RunFeedbackRepository {
    SaveResult saveOrGet(RunFeedback proposed);

    Optional<RunFeedback> findByRequest(UserId owner, RunId runId, String clientRequestId);

    /** 只更新 QUEUE_STATUS_UNKNOWN，并返回持久化后的状态。 */
    Optional<RunFeedback> updateExportDisposition(UserId owner, RunId runId, String feedbackId,
                                                   RunFeedback.ExportDisposition disposition);

    List<RunFeedback> list(UserId owner, RunId runId, Position before, int limit);

    record SaveResult(RunFeedback feedback, boolean created) { }
    record Position(Instant createdAt, String feedbackId) { }
}
