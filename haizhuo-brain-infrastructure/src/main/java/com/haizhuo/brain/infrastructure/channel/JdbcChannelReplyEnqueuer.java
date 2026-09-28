package com.haizhuo.brain.infrastructure.channel;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.channel.ChannelDelivery;
import com.haizhuo.brain.platform.channel.ChannelDeliveryOutbox;
import com.haizhuo.brain.platform.channel.ChannelReplyEnqueuer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 渠道回投入队（P3）：由会话映射反查该会话来自哪个渠道与回复目标，再入队一条投递。
 *
 * <p>幂等键取 {@code runId:reply}：同一个 Run 的最终答复只会入队一次，
 * 因此 worker 重放、SSE 重连或重复落定都不会重复发送。</p>
 */
@Repository
public class JdbcChannelReplyEnqueuer implements ChannelReplyEnqueuer {

    private final JdbcTemplate jdbc;
    private final ChannelDeliveryOutbox outbox;

    public JdbcChannelReplyEnqueuer(JdbcTemplate jdbc, ChannelDeliveryOutbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Override
    public void enqueueReply(RunId runId, SessionId sessionId, String text) {
        if (sessionId == null || text == null || text.isBlank()) {
            return;
        }
        Optional<ChannelReplyRoute> route = route(sessionId);
        if (route.isEmpty()) {
            // Web 会话没有渠道映射，这是正常路径而不是异常。
            return;
        }
        ChannelReplyRoute target = route.get();
        outbox.enqueue(new ChannelDelivery(UUID.randomUUID().toString(), runId, target.bindingId(),
                target.provider(), target.replyTarget(), text, runId.value() + ":reply"));
    }

    /** 回复目标取该会话最近一次入站消息携带的地址：它才属于当前这轮对话。 */
    private Optional<ChannelReplyRoute> route(SessionId sessionId) {
        return jdbc.query("SELECT conversation.binding_id,account.provider,inbox.reply_target "
                        + "FROM platform_channel_conversation conversation "
                        + "JOIN platform_channel_account account ON account.binding_id=conversation.binding_id "
                        + "JOIN platform_channel_inbox inbox ON inbox.session_id=conversation.session_id "
                        + "WHERE conversation.session_id=? ORDER BY inbox.accepted_at DESC LIMIT 1",
                (rs, row) -> new ChannelReplyRoute(rs.getString("binding_id"), rs.getString("provider"),
                        rs.getString("reply_target")), sessionId.value()).stream().findFirst();
    }

    private record ChannelReplyRoute(String bindingId, String provider, String replyTarget) {
    }
}
