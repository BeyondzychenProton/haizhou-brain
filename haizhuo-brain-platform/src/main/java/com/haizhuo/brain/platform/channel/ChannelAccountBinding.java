package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.TenantId;

/**
 * 服务端持有的渠道账号路由；绝不能从消息体里拷贝。
 *
 * <p>{@code sessionScope} 是该渠道账户的会话粒度配置，装配时投影成框架的
 * {@code ChannelConfig.dmScope}；历史数据缺失时按 {@link SessionScope#defaultScope()} 处理。</p>
 */
public record ChannelAccountBinding(String bindingId, TenantId tenantId, String provider,
                                    String externalAccountKey, String credentialRef,
                                    long defaultEmployeeId, SessionScope sessionScope, boolean enabled) {
}
