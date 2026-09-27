package com.haizhuo.brain.platform.channel;

import java.util.Optional;

/** 持久化的投递认领与状态更新，与 Run 完成状态解耦。 */
public interface ChannelDeliveryOutbox {
    Optional<ChannelDelivery> claimNextDelivery();

    /** 记录已投递、可重试、永久性或结果不确定的投递结果，绝不盲目重发。 */
    void recordOutcome(String deliveryId, ChannelOutboundSender.DeliveryResult result);
}
