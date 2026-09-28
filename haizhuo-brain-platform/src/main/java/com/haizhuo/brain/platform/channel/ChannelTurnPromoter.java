package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.SessionId;

/**
 * 等待消息提升（P3 补全）：会话空闲后，把该会话最早的一条"等待中"入站消息提升为 Run。
 *
 * <p>平台在数据库层强制"每个会话同一时刻只有一个活动 Run"，因此会话忙时到达的消息只能先登记为
 * 已受理、等待中（{@link ChannelAcceptance} 的 runId 为空）。本端口负责在 Run 进入终态后把等待队列
 * 头部转成真正的 Run，否则这些消息会永远停在收件箱里。</p>
 *
 * <p>实现必须保证"只提升一次"：并发或多实例下重复触发都不能产生第二个 Run。</p>
 */
public interface ChannelTurnPromoter {

    void promoteWaitingTurn(SessionId sessionId);

    /** 未接入渠道时的默认实现：无等待消息，不做任何事。 */
    ChannelTurnPromoter NOOP = sessionId -> { };
}
