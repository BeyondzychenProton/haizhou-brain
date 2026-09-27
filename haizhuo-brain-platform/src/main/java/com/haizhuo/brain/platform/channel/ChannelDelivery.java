package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.RunId;

/** 调用供应商之前落库的、不可变的出站意图。 */
public record ChannelDelivery(String deliveryId, RunId runId, String bindingId, String provider,
                              String replyTarget, String text, String idempotencyKey) {
}
