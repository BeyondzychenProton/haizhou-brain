package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;

/**
 * 渠道回投入队（P3）：Run 产生最终答复时，若该会话来自某个渠道，
 * 就把答复排进出站 outbox；Web 会话没有渠道映射，直接忽略。
 *
 * <p>入队而不是直接发送：投递必须在发送前落库，才能在崩溃后核查而不是盲目重发。</p>
 */
public interface ChannelReplyEnqueuer {

    void enqueueReply(RunId runId, SessionId sessionId, String text);

    /** 只在根结算的当前事务调用；resultId 指向不可变完整正文。 */
    default void enqueueResult(RunId runId, SessionId sessionId, String resultId, String text) {
        enqueueReply(runId,sessionId,text);
    }

    /** 未接入渠道时的默认实现：不产生任何投递。 */
    ChannelReplyEnqueuer NOOP = (runId, sessionId, text) -> { };
}
