package com.haizhuo.brain.infrastructure.channel;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.channel.ChannelIdentityDirectory;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 渠道身份关联的只读目录（P3）。只认 LINKED 状态：
 * 解绑后必须立刻停止把外部用户解析成平台用户，否则解绑等于没有生效。
 */
@Repository
public class JdbcChannelIdentityDirectory implements ChannelIdentityDirectory {

    private final JdbcTemplate jdbc;

    public JdbcChannelIdentityDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserId> resolve(TenantId tenantId, String bindingId, String externalUserId) {
        if (bindingId == null || externalUserId == null) {
            return Optional.empty();
        }
        // 绑定必须与账号同租户：跨租户的同名外部标识不得解析成功。
        return jdbc.query("SELECT identity.user_id FROM platform_channel_identity identity "
                        + "JOIN platform_channel_account account ON account.binding_id=identity.binding_id "
                        + "WHERE identity.binding_id=? AND identity.external_user_id=? "
                        + "AND identity.state='LINKED' AND account.tenant_id=?",
                (rs, row) -> new UserId(rs.getLong(1)), bindingId, externalUserId,
                tenantId == null ? null : tenantId.value()).stream().findFirst();
    }
}
