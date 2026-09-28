package com.haizhuo.brain.infrastructure.channel;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.channel.ChannelAcceptance;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelTurnStore;
import com.haizhuo.brain.platform.channel.VerifiedChannelMessage;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.session.AgentSession;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 渠道入站受理（P3）：去重、会话映射与 Run 创建在同一个事务内完成。
 *
 * <p>这里刻意复用既有 {@link SessionApplicationService}：单会话唯一活动 Run 的原子排队、
 * 幂等重放与"员工必须已发布"的校验都已经由它保证。渠道侧只负责把外部标识稳定映射到
 * 平台 Session，不重复实现排队语义。</p>
 *
 * <p>失败顺序很重要：任何校验不通过都必须先于收件箱写入，因此重投与非法请求都不会留下受理痕迹。</p>
 */
@Repository
public class JdbcChannelTurnStore implements ChannelTurnStore {

    private static final int CONTENT_LIMIT = 4000;

    private final JdbcTemplate jdbc;
    private final SessionApplicationService sessions;
    private final Clock clock;

    public JdbcChannelTurnStore(JdbcTemplate jdbc, SessionApplicationService sessions, Clock clock) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    @Override
    public ChannelAcceptance accept(VerifiedChannelMessage message, ChannelAccountBinding binding, UserId userId) {
        // 同一绑定的受理串行化：并发重投与并发首次建会话都只会有一个赢家。
        jdbc.queryForObject("SELECT binding_id FROM platform_channel_account WHERE binding_id=? FOR UPDATE",
                String.class, binding.bindingId());

        Optional<ChannelAcceptance> accepted = findAccepted(message.bindingId(), message.providerEventId());
        if (accepted.isPresent()) {
            // 重投绝不能再建 Run：原样返回既有受理，只把 duplicate 置真。
            ChannelAcceptance previous = accepted.get();
            return new ChannelAcceptance(previous.sessionId(), previous.runId(), true);
        }

        SessionId sessionId = conversationSession(binding, message)
                .orElseGet(() -> openConversation(binding, message, userId));
        AgentRun run = sessions.createRun(sessionId, userId, message.providerEventId(), message.text());
        recordInbox(message, binding, userId, sessionId, run);
        return new ChannelAcceptance(sessionId, Optional.of(run.id()), false);
    }

    private Optional<ChannelAcceptance> findAccepted(String bindingId, String providerEventId) {
        return jdbc.query("SELECT session_id,run_id FROM platform_channel_inbox "
                        + "WHERE binding_id=? AND provider_event_id=?",
                (rs, row) -> new ChannelAcceptance(new SessionId(rs.getString("session_id")),
                        Optional.ofNullable(rs.getString("run_id")).map(RunId::new), false),
                bindingId, providerEventId).stream().findFirst();
    }

    private Optional<SessionId> conversationSession(ChannelAccountBinding binding, VerifiedChannelMessage message) {
        return jdbc.query("SELECT session_id FROM platform_channel_conversation "
                        + "WHERE binding_id=? AND external_conversation_id=?",
                (rs, row) -> new SessionId(rs.getString(1)), binding.bindingId(),
                message.externalConversationId()).stream().findFirst();
    }

    private SessionId openConversation(ChannelAccountBinding binding, VerifiedChannelMessage message, UserId userId) {
        AgentSession session = sessions.create(userId, binding.defaultEmployeeId());
        Instant now = clock.instant();
        jdbc.update("INSERT INTO platform_channel_conversation(binding_id,external_conversation_id,session_id,"
                        + "created_at,updated_at) VALUES(?,?,?,?,?)",
                binding.bindingId(), message.externalConversationId(), session.id().value(),
                Timestamp.from(now), Timestamp.from(now));
        return session.id();
    }

    private void recordInbox(VerifiedChannelMessage message, ChannelAccountBinding binding, UserId userId,
                             SessionId sessionId, AgentRun run) {
        jdbc.update("INSERT INTO platform_channel_inbox(binding_id,provider_event_id,provider,"
                        + "external_conversation_id,external_user_id,user_id,session_id,run_id,reply_target,"
                        + "content,accepted_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                binding.bindingId(), message.providerEventId(), message.provider(),
                message.externalConversationId(), message.externalUserId(), userId.value(), sessionId.value(),
                run.id().value(), message.replyTarget(), truncate(message.text()), Timestamp.from(clock.instant()));
    }

    private static String truncate(String content) {
        return content != null && content.length() > CONTENT_LIMIT ? content.substring(0, CONTENT_LIMIT) : content;
    }
}
