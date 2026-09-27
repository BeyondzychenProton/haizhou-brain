package com.haizhuo.brain.platform.tool;

/**
 * 工具结果的一次性投递守卫（规格 §45.1/§46）。只有 READY 的结果才能投递给 Harness；
 * DELIVERED 的结果绝不重复提交——这正是 P0 结论要求的平台侧重复恢复防护。
 */
public enum ResultDeliveryState {
    READY, DELIVERED
}
