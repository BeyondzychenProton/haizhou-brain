package com.haizhuo.brain.platform.channel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 现有渠道 Outbox 的只读投影；不能 claim 或修改 Delivery。 */
public interface ChannelDeliveryAdministrationQuery {
    List<DeliverySummary> findPage(PageQuery query);

    Optional<DeliveryDetail> findById(String deliveryId);

    record PageQuery(String state, String bindingId, String provider, String runId,
                     Instant createdFrom, Instant createdTo, Instant beforeCreatedAt,
                     String beforeDeliveryId, int limit) { }

    record DeliverySummary(String deliveryId, String runId, String sessionId, String bindingId,
                           String provider, String state, int attempts, String resultId,
                           String contentPreview, String externalMessageId, String lastErrorCode,
                           Instant createdAt, Instant updatedAt, Instant sendingExpiresAt,
                           String evidenceSource, long revision) { }

    record DeliveryDetail(DeliverySummary summary, String runState,
                          List<AttemptHistory> attemptsHistory, String attemptsHistoryState,
                          List<Verification> verifications) { }

    /** 不能从聚合 attempts 字段还原历史尝试记录。 */
    record AttemptHistory(int attemptNo, long claimGeneration, Instant startedAt, Instant finishedAt,
                          String status, String safeErrorCode) { }

    /** 此只读查询契约不开放任何核查写操作。 */
    record Verification(String decision, String occurredAt, String evidenceReferenceLabel) { }

    record Page(List<DeliverySummary> items, String nextCursor, boolean hasMore) { }
}
