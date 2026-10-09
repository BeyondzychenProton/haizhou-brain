package com.haizhuo.brain.platform.channel;

/** 按授权本次发送的精确 claim 写入结果后返回的状态。 */
public enum DeliveryOutcomeWrite {
    APPLIED,
    STALE_CLAIM
}
