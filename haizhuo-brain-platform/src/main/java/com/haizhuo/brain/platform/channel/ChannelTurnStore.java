package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.UserId;

/**
 * 原子化的收件箱受理、去重、会话映射，以及单会话 Run 排队。
 * 实现必须把收件箱、Session 与待执行 Run 一起提交；在数据库层强制
 * (binding,event) 与会话键唯一，并保证每个 Session 只有一个活动 Run。
 * 含短期密钥的回复目标必须加密或带过期时间存储。
 */
public interface ChannelTurnStore {
    ChannelAcceptance accept(VerifiedChannelMessage message, ChannelAccountBinding binding, UserId userId);
}
