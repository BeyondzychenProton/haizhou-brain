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

    private static final int CONTENT_LIMIT = 4000;

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
        jdbc.update("INSERT INTO platform_channel_delivery(delivery_id,run_id,binding_id,provider,reply_target,"
                        + "content,idempotency_key,state,attempts,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,'PENDING',0,?,?)",
                delivery.deliveryId(), delivery.runId().value(), delivery.bindingId(), delivery.provider(),
                delivery.replyTarget(), truncate(delivery.text()), delivery.idempotencyKey(),
                Timestamp.from(now), Timestamp.from(now));
        return delivery;
    }

    @Override
    public Optional<ChannelDelivery> claimNextDelivery() {
        Optional<ChannelDelivery> candidate = jdbc.query("SELECT delivery_id,run_id,binding_id,provider,"
                        + "reply_target,content,idempotency_key FROM platform_channel_delivery "
                        + "WHERE state IN ('PENDING','RETRYABLE_FAILURE') ORDER BY created_at,delivery_id LIMIT 1",
                (rs, row) -> map(rs)).stream().findFirst();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        ChannelDelivery delivery = candidate.get();
        int claimed = jdbc.update("UPDATE platform_channel_delivery SET state='SENDING',attempts=attempts+1,"
                        + "updated_at=? WHERE delivery_id=? AND state IN ('PENDING','RETRYABLE_FAILURE')",
                Timestamp.from(clock.instant()), delivery.deliveryId());
        return claimed == 1 ? candidate : Optional.empty();
    }

    @Override
    public void recordOutcome(String deliveryId, ChannelOutboundSender.DeliveryResult result) {
        boolean delivered = result.status() == ChannelOutboundSender.DeliveryResult.Status.DELIVERED;
        jdbc.update("UPDATE platform_channel_delivery SET state=?,external_message_id=?,last_error=?,updated_at=? "
                        + "WHERE delivery_id=?",
                result.status().name(), result.externalMessageId(), delivered ? null : result.status().name(),
                Timestamp.from(clock.instant()), deliveryId);
    }

    private Optional<ChannelDelivery> findByKey(String idempotencyKey) {
        return jdbc.query("SELECT delivery_id,run_id,binding_id,provider,reply_target,content,idempotency_key "
                        + "FROM platform_channel_delivery WHERE idempotency_key=?",
                (rs, row) -> map(rs), idempotencyKey).stream().findFirst();
    }

    private static ChannelDelivery map(ResultSet rs) throws SQLException {
        return new ChannelDelivery(rs.getString("delivery_id"), new RunId(rs.getString("run_id")),
                rs.getString("binding_id"), rs.getString("provider"), rs.getString("reply_target"),
                rs.getString("content"), rs.getString("idempotency_key"));
    }

    private static String truncate(String content) {
        return content != null && content.length() > CONTENT_LIMIT ? content.substring(0, CONTENT_LIMIT) : content;
    }
}
