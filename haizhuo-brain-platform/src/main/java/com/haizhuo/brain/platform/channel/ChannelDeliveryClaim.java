package com.haizhuo.brain.platform.channel;

import java.util.Objects;

/** 单次 sender 尝试使用的内部围栏凭据；claim token 绝不能越过 API 边界。 */
public record ChannelDeliveryClaim(ChannelDelivery delivery, int attemptNo, long claimGeneration, String claimToken) {
    public ChannelDeliveryClaim {
        Objects.requireNonNull(delivery, "delivery");
        if (attemptNo < 1) throw new IllegalArgumentException("attemptNo must be positive");
        if (claimGeneration < 1) throw new IllegalArgumentException("claimGeneration must be positive");
        if (claimToken == null || claimToken.isBlank()) throw new IllegalArgumentException("claimToken is required");
    }

    /** 避免日志意外包含私有 token、回复目标或消息正文。 */
    @Override
    public String toString() {
        return "ChannelDeliveryClaim[deliveryId=" + delivery.deliveryId() + ", attemptNo=" + attemptNo
                + ", claimGeneration=" + claimGeneration + ", claimToken=[REDACTED]]";
    }
}
