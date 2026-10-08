package com.haizhuo.brain.infrastructure.channel;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.channel.ChannelDelivery;
import com.haizhuo.brain.platform.channel.ChannelDeliveryOutbox;
import com.haizhuo.brain.platform.channel.ChannelOutboundSender;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出站投递 outbox（P3）：先落库再发送，投递状态与 Run 完成状态彼此独立。
 *
 * <p>认领用"状态翻转"而不是长事务锁：多 worker 并发时只有一个能把
 * PENDING/RETRYABLE_FAILURE 推进到 SENDING，因此不会重复发送。
 * 结果不确定（UNCERTAIN）的行不会被再次认领，只能由人工核查。</p>
 */
@Repository
public class JdbcChannelDeliveryOutbox implements ChannelDeliveryOutbox {

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

    @Override
    public Optional<ChannelDelivery> claimNextDelivery() {
        Instant now = clock.instant();
        jdbc.update("UPDATE platform_channel_delivery SET state='UNCERTAIN',last_error='SENDING_LEASE_EXPIRED',updated_at=? "
                        + "WHERE state='SENDING' AND sending_expires_at<=?",Timestamp.from(now),Timestamp.from(now));
        Optional<ChannelDelivery> candidate = jdbc.query("SELECT delivery.*,result.body result_body FROM platform_channel_delivery delivery "
                        + "LEFT JOIN platform_agent_result result ON result.result_id=delivery.result_id AND result.run_id=delivery.run_id AND result.visibility='USER' "
                        + "WHERE delivery.state IN ('PENDING','RETRYABLE_FAILURE') ORDER BY delivery.created_at,delivery.delivery_id LIMIT 1",
                (rs, row) -> map(rs)).stream().findFirst();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        ChannelDelivery delivery = candidate.get();
        int claimed = jdbc.update("UPDATE platform_channel_delivery SET state='SENDING',attempts=attempts+1,"
                        + "updated_at=?,sending_expires_at=? WHERE delivery_id=? AND state IN ('PENDING','RETRYABLE_FAILURE')",
                Timestamp.from(now),Timestamp.from(now.plusSeconds(300)),delivery.deliveryId());
        return claimed == 1 ? candidate : Optional.empty();
    }

    @Override
    public void recordOutcome(String deliveryId, ChannelOutboundSender.DeliveryResult result) {
        boolean delivered = result.status() == ChannelOutboundSender.DeliveryResult.Status.DELIVERED;
        jdbc.update("UPDATE platform_channel_delivery SET state=?,external_message_id=?,last_error=?,updated_at=? "
                        + "WHERE delivery_id=? AND state='SENDING'",
                result.status().name(), result.externalMessageId(), delivered ? null : result.status().name(),
                Timestamp.from(clock.instant()), deliveryId);
    }

    private Optional<ChannelDelivery> findByKey(String idempotencyKey) {
        return jdbc.query("SELECT delivery.*,result.body result_body FROM platform_channel_delivery delivery "
                        + "LEFT JOIN platform_agent_result result ON result.result_id=delivery.result_id AND result.run_id=delivery.run_id AND result.visibility='USER' "
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
}
