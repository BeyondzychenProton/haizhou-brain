package com.haizhuo.brain.infrastructure.channel;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.channel.ChannelDelivery;
import com.haizhuo.brain.platform.channel.ChannelDeliveryClaim;
import com.haizhuo.brain.platform.channel.ChannelDeliveryOutbox;
import com.haizhuo.brain.platform.channel.ChannelOutboundSender;
import com.haizhuo.brain.platform.channel.DeliveryOutcomeWrite;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出站投递 outbox：先落库再发送，投递状态与 Run 完成状态彼此独立。
 * 每个发送尝试由 claimGeneration + 私有随机 claimToken 围栏；过期租约不会自动重发。
 */
@Repository
public class JdbcChannelDeliveryOutbox implements ChannelDeliveryOutbox {

    private static final int LEASE_SECONDS = 300;
    private static final String LEASE_EXPIRED = "SENDING_LEASE_EXPIRED";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcChannelDeliveryOutbox(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    @Override
    public ChannelDelivery enqueue(ChannelDelivery delivery) {
        Optional<ChannelDelivery> existing = findByKey(delivery.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        if (delivery.text() == null || delivery.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024*1024)
            throw new com.haizhuo.brain.platform.run.ResultSizeExceededException();
        if (delivery.resultId() != null && jdbc.query("SELECT result_id FROM platform_agent_result WHERE result_id=? AND run_id=? AND kind='ROOT_FINAL' AND visibility='USER'",
                (rs,n)->rs.getString(1),delivery.resultId(),delivery.runId().value()).isEmpty())
            throw new IllegalArgumentException("Delivery result reference invalid");
        jdbc.update("INSERT INTO platform_channel_delivery(delivery_id,run_id,binding_id,provider,reply_target,"
                        + "content,idempotency_key,state,attempts,created_at,updated_at,result_id) "
                        + "VALUES(?,?,?,?,?,?,?,'PENDING',0,?,?,?)",
                delivery.deliveryId(), delivery.runId().value(), delivery.bindingId(), delivery.provider(),
                delivery.replyTarget(),delivery.resultId()==null?delivery.text():com.haizhuo.brain.infrastructure.run.JdbcRunEventAppender.summary(delivery.text()),delivery.idempotencyKey(),
                Timestamp.from(now),Timestamp.from(now),delivery.resultId());
        return delivery;
    }

    /** 在同一事务内使旧 claim 过期并创建新 claim，保证持久化尝试与聚合状态一致。 */
    @Transactional
    @Override
    public Optional<ChannelDeliveryClaim> claimNextDelivery() {
        Instant now = clock.instant();
        markExpiredClaims(now);

        List<Candidate> candidates = jdbc.query("SELECT delivery.*,result.body result_body "
                        + "FROM platform_channel_delivery delivery "
                        + "LEFT JOIN platform_agent_result result ON result.result_id=delivery.result_id "
                        + "AND result.run_id=delivery.run_id AND result.visibility='USER' "
                        + "WHERE delivery.state IN ('PENDING','RETRYABLE_FAILURE') "
                        + "ORDER BY delivery.created_at,delivery.delivery_id LIMIT 1",
                (rs, row) -> new Candidate(map(rs), rs.getInt("attempts"),
                        rs.getLong("claim_generation"), rs.getLong("revision")));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Candidate candidate = candidates.get(0);
        String token = UUID.randomUUID().toString();
        int nextAttemptNo = candidate.attempts() + 1;
        long nextGeneration = candidate.claimGeneration() + 1;
        int changed = jdbc.update("UPDATE platform_channel_delivery SET state='SENDING',attempts=attempts+1,"
                        + "claim_generation=claim_generation+1,claim_token=?,updated_at=?,sending_expires_at=?,"
                        + "revision=revision+1 WHERE delivery_id=? AND state IN ('PENDING','RETRYABLE_FAILURE') "
                        + "AND claim_generation=? AND revision=?",
                token, Timestamp.from(now), Timestamp.from(now.plusSeconds(LEASE_SECONDS)),
                candidate.delivery().deliveryId(), candidate.claimGeneration(), candidate.revision());
        if (changed != 1) {
            return Optional.empty();
        }
        jdbc.update("INSERT INTO platform_channel_delivery_attempt(delivery_id,attempt_no,claim_generation,"
                        + "claim_token,started_at,status) VALUES(?,?,?,?,?,'SENDING')",
                candidate.delivery().deliveryId(), nextAttemptNo, nextGeneration, token, Timestamp.from(now));
        return Optional.of(new ChannelDeliveryClaim(candidate.delivery(), nextAttemptNo, nextGeneration, token));
    }

    @Transactional
    @Override
    public DeliveryOutcomeWrite recordOutcome(ChannelDeliveryClaim claim,
                                               ChannelOutboundSender.DeliveryResult result) {
        ChannelOutboundSender.DeliveryResult.Status status = java.util.Objects.requireNonNull(result.status());
        boolean delivered = status == ChannelOutboundSender.DeliveryResult.Status.DELIVERED;
        String safeErrorCode = delivered ? null : status.name();
        Instant finishedAt = clock.instant();
        int changed = jdbc.update("UPDATE platform_channel_delivery SET state=?,external_message_id=?,last_error=?,"
                        + "updated_at=?,sending_expires_at=NULL,claim_token=NULL,revision=revision+1 "
                        + "WHERE delivery_id=? AND state='SENDING' AND claim_generation=? AND claim_token=?",
                status.name(), result.externalMessageId(), safeErrorCode, Timestamp.from(finishedAt),
                claim.delivery().deliveryId(), claim.claimGeneration(), claim.claimToken());
        if (changed == 0) {
            return DeliveryOutcomeWrite.STALE_CLAIM;
        }
        int attemptChanged = jdbc.update("UPDATE platform_channel_delivery_attempt SET finished_at=?,status=?,"
                        + "safe_error_code=? WHERE delivery_id=? AND attempt_no=? AND claim_generation=? "
                        + "AND claim_token=? AND status='SENDING' AND finished_at IS NULL",
                Timestamp.from(finishedAt), status.name(), safeErrorCode, claim.delivery().deliveryId(),
                claim.attemptNo(), claim.claimGeneration(), claim.claimToken());
        if (attemptChanged != 1) {
            throw new IllegalStateException("Delivery outcome was accepted without its active attempt row");
        }
        return DeliveryOutcomeWrite.APPLIED;
    }

    private void markExpiredClaims(Instant now) {
        List<ExpiredClaim> expired = jdbc.query("SELECT delivery_id,attempts,claim_generation,claim_token "
                        + "FROM platform_channel_delivery WHERE state='SENDING' AND sending_expires_at IS NOT NULL "
                        + "AND sending_expires_at<=?",
                (rs, row) -> new ExpiredClaim(rs.getString("delivery_id"), rs.getInt("attempts"),
                        rs.getLong("claim_generation"), rs.getString("claim_token")), Timestamp.from(now));
        for (ExpiredClaim claim : expired) {
            String tokenPredicate = claim.claimToken() == null ? "claim_token IS NULL" : "claim_token=?";
            String sql = "UPDATE platform_channel_delivery SET state='UNCERTAIN',last_error=?,updated_at=?,"
                    + "sending_expires_at=NULL,claim_token=NULL,revision=revision+1 WHERE delivery_id=? "
                    + "AND state='SENDING' AND claim_generation=? AND sending_expires_at<=? AND " + tokenPredicate;
            int changed;
            if (claim.claimToken() == null) {
                changed = jdbc.update(sql, LEASE_EXPIRED, Timestamp.from(now), claim.deliveryId(),
                        claim.claimGeneration(), Timestamp.from(now));
            } else {
                changed = jdbc.update(sql, LEASE_EXPIRED, Timestamp.from(now), claim.deliveryId(),
                        claim.claimGeneration(), Timestamp.from(now), claim.claimToken());
            }
            if (changed == 1 && claim.claimToken() != null) {
                jdbc.update("UPDATE platform_channel_delivery_attempt SET finished_at=?,status='UNCERTAIN',"
                                + "safe_error_code=? WHERE delivery_id=? AND attempt_no=? AND claim_generation=? "
                                + "AND claim_token=? AND status='SENDING' AND finished_at IS NULL",
                        Timestamp.from(now), LEASE_EXPIRED, claim.deliveryId(), claim.attemptNo(),
                        claim.claimGeneration(), claim.claimToken());
            }
        }
    }

    private Optional<ChannelDelivery> findByKey(String idempotencyKey) {
        return jdbc.query("SELECT delivery.*,result.body result_body FROM platform_channel_delivery delivery "
                        + "LEFT JOIN platform_agent_result result ON result.result_id=delivery.result_id "
                        + "AND result.run_id=delivery.run_id AND result.visibility='USER' "
                        + "WHERE delivery.idempotency_key=?",
                (rs, row) -> map(rs), idempotencyKey).stream().findFirst();
    }

    private static ChannelDelivery map(ResultSet rs) throws SQLException {
        String resultId = rs.getString("result_id");
        String body = resultId==null?rs.getString("content"):rs.getString("result_body");
        if (body==null) throw new SQLException("Complete delivery result missing");
        return new ChannelDelivery(rs.getString("delivery_id"), new RunId(rs.getString("run_id")),
                rs.getString("binding_id"), rs.getString("provider"), rs.getString("reply_target"),
                body,rs.getString("idempotency_key"),resultId);
    }

    private record Candidate(ChannelDelivery delivery, int attempts, long claimGeneration, long revision) { }
    private record ExpiredClaim(String deliveryId, int attemptNo, long claimGeneration, String claimToken) { }
}
