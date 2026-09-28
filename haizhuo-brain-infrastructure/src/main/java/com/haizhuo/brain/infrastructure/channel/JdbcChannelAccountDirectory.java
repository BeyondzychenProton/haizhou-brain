package com.haizhuo.brain.infrastructure.channel;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAccountDirectory;
import java.sql.ResultSet;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 渠道账号绑定的只读目录（P3）。绑定由管理侧维护，
 * 因此这里绝不接受消息体里的路由信息——入站只允许按 bindingId 反查服务端持有的绑定。
 */
@Repository
public class JdbcChannelAccountDirectory implements ChannelAccountDirectory {

    private final JdbcTemplate jdbc;

    public JdbcChannelAccountDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ChannelAccountBinding> findById(String bindingId) {
        if (bindingId == null || bindingId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT binding_id,tenant_id,provider,external_account_key,credential_ref,"
                        + "default_employee_id,enabled FROM platform_channel_account WHERE binding_id=?",
                (rs, row) -> map(rs), bindingId).stream().findFirst();
    }

    private static ChannelAccountBinding map(ResultSet rs) throws java.sql.SQLException {
        return new ChannelAccountBinding(rs.getString("binding_id"), new TenantId(rs.getLong("tenant_id")),
                rs.getString("provider"), rs.getString("external_account_key"), rs.getString("credential_ref"),
                rs.getLong("default_employee_id"), rs.getBoolean("enabled"));
    }
}
