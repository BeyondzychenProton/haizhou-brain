package com.haizhuo.brain.platform.channel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 渠道绑定数据的管理写侧。
 *
 * <p>入站链路只读 {@link ChannelAccountDirectory} 且每次实时查库，因此这里写入的绑定会立即生效，
 * 不需要等待运行时重新装配；反之运行时装配失败也不会让已写入的绑定失效。</p>
 */
public interface ChannelAdministrationStore {

    /** (provider, externalAccountKey) 是否已被占用；允许第二条会让入站无法判定归属。 */
    boolean existsByExternalAccount(String provider, String externalAccountKey);

    void createAccount(ChannelAccountBinding binding);

    /** 只更新启停、默认员工与会话粒度；路由标识不允许经管理面改动。 */
    void updateAccount(ChannelAccountBinding binding);

    List<ChannelIdentityBinding> findIdentities(String bindingId);

    Optional<ChannelIdentityBinding> findIdentity(String bindingId, String externalUserId);

    void linkIdentity(ChannelIdentityBinding identity);

    /** 解绑置为 {@code REVOKED} 而非删除：删除会同时丢掉审计与旧投递的抑制依据。 */
    void revokeIdentity(String bindingId, String externalUserId, Instant now);
}
