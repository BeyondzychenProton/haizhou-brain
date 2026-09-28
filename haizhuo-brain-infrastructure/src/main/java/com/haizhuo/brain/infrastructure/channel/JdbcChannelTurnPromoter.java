package com.haizhuo.brain.infrastructure.channel;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.channel.ChannelTurnPromoter;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 等待消息提升（P3 补全）。平台强制"每个会话同一时刻只有一个活动 Run"，
 * 所以会话忙时到达的渠道消息只能登记为等待；Run 进入终态后由本类把队列头部转成真正的 Run。
 *
 * <p>"只提升一次"靠行锁保证：先 {@code SELECT … FOR UPDATE} 锁住最早的那条等待记录，
 * 同一事务内建 Run 再回填 {@code run_id}。并发或多实例同时触发时，只有一个事务能锁到该行，
 * 另一个会看到它已被回填而跳过。</p>
 *
 * <p>建 Run 复用 {@link SessionApplicationService}：幂等重放（{@code clientRequestId = providerEventId}）
 * 与单会话唯一活动 Run 的排队语义都由它保证，这里不重复实现。</p>
 */
@Repository
public class JdbcChannelTurnPromoter implements ChannelTurnPromoter {

    private final JdbcTemplate jdbc;
    private final SessionApplicationService sessions;

    public JdbcChannelTurnPromoter(JdbcTemplate jdbc, SessionApplicationService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Transactional
    @Override
    public void promoteWaitingTurn(SessionId sessionId) {
        Optional<WaitingTurn> waiting = lockEarliestWaitingTurn(sessionId);
        if (waiting.isEmpty()) {
            // 没有等待消息是正常路径：绝大多数 Run 终态都属于这种情况。
            return;
        }
        WaitingTurn turn = waiting.get();
        AgentRun run = sessions.createRun(sessionId, new UserId(turn.userId()), turn.providerEventId(),
                turn.content());
        // 回填带 run_id IS NULL 条件：即使行锁失效也不会覆盖别人已提升的结果。
        jdbc.update("UPDATE platform_channel_inbox SET run_id=? WHERE binding_id=? AND provider_event_id=? "
                        + "AND run_id IS NULL",
                run.id().value(), turn.bindingId(), turn.providerEventId());
    }

    private Optional<WaitingTurn> lockEarliestWaitingTurn(SessionId sessionId) {
        return jdbc.query("SELECT binding_id,provider_event_id,user_id,content FROM platform_channel_inbox "
                        + "WHERE session_id=? AND run_id IS NULL ORDER BY accepted_at, provider_event_id "
                        + "LIMIT 1 FOR UPDATE",
                (rs, row) -> new WaitingTurn(rs.getString("binding_id"), rs.getString("provider_event_id"),
                        rs.getLong("user_id"), rs.getString("content")),
                sessionId.value()).stream().findFirst();
    }

    private record WaitingTurn(String bindingId, String providerEventId, long userId, String content) {
    }
}
