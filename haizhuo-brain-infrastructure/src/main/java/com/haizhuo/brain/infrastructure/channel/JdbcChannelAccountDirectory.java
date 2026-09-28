package com.haizhuo.brain.infrastructure.channel;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAccountDirectory;
import com.haizhuo.brain.platform.channel.SessionScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 渠道账号绑定的只读目录（P3）。绑定由管理侧维护，
 * 因此这里绝不接受消息体里的路由信息——入站只允许按 bindingId 反查服务端持有的绑定。
 */
@Repository
public class JdbcChannelAccountDirectory implements ChannelAccountDirectory {

    private static final String COLUMNS = "binding_id,tenant_id,provider,external_account_key,credential_ref,"
            + "default_employee_id,dm_scope,enabled";

    private final JdbcTemplate jdbc;

    public JdbcChannelAccountDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ChannelAccountBinding> findById(String bindingId) {
        if (bindingId == null || bindingId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_channel_account WHERE binding_id=?",
                (rs, row) -> map(rs), bindingId).stream().findFirst();
    }

    /**
     * 全量读取（含已禁用）供装配层重建渠道注册表。
     * 消息路径不经过这里，因此这次全表读取不会放大入站开销。
     */
    @Override
    public List<ChannelAccountBinding> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_channel_account ORDER BY provider, binding_id",
                (rs, row) -> map(rs));
    }

    private static ChannelAccountBinding map(ResultSet rs) throws SQLException {
        return new ChannelAccountBinding(rs.getString("binding_id"), new TenantId(rs.getLong("tenant_id")),
                rs.getString("provider"), rs.getString("external_account_key"), rs.getString("credential_ref"),
                rs.getLong("default_employee_id"), sessionScope(rs.getString("dm_scope")),
                rs.getBoolean("enabled"));
    }

    /**
     * 未知取值回落到默认粒度而不是抛错：入站链路曾因单条脏配置而整体不可受理，
     * 而"更严格的隔离"在这个位置是安全的兜底；写入侧仍由枚举校验保证合法。
     */
    private static SessionScope sessionScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return SessionScope.defaultScope();
        }
        try {
            return SessionScope.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            return SessionScope.defaultScope();
        }
    }
}
