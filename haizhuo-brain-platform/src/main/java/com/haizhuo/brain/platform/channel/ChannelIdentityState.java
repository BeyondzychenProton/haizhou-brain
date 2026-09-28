package com.haizhuo.brain.platform.channel;

/**
 * 身份关联状态。只有 {@link #LINKED} 参与解析；
 * {@link #REVOKED} 保留行，既留审计痕迹，也让解绑前产生的旧投递继续被抑制。
 */
public enum ChannelIdentityState {
    LINKED,
    REVOKED
}
