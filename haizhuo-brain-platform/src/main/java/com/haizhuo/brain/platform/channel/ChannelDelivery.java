package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.RunId;

/** Immutable outbound intent saved before calling the provider. */
public record ChannelDelivery(String deliveryId, RunId runId, String bindingId, String provider,
                              String replyTarget, String text, String idempotencyKey) {
}
