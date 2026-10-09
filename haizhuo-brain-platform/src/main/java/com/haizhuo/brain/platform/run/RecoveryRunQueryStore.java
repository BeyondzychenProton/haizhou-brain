package com.haizhuo.brain.platform.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 恢复管理投影使用的只读持久化端口。 */
public interface RecoveryRunQueryStore {
    /** 按 createdAt、runId 降序游标返回其之前最多 {@code limit} 条记录。 */
    List<RecoveryRunQuery.Summary> findPage(RecoveryRunQuery.Filter filter,
                                            Instant beforeCreatedAt, String beforeRunId, int limit);

    Optional<RecoveryRunQuery.Detail> findDetail(String runId);
}
