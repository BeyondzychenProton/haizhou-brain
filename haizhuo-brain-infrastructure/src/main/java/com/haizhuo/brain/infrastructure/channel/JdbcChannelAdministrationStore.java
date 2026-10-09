package com.haizhuo.brain.infrastructure.channel;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAdministrationStore;
import com.haizhuo.brain.platform.channel.ChannelIdentityBinding;
import com.haizhuo.brain.platform.channel.ChannelIdentityState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 渠道绑定数据的管理写侧实现（P3 方向 4）。
 *
 * <p>写入后不做任何缓存：入站链路每次实时查库，因此这里的提交就是生效，
 * 不需要额外通知渠道运行时（运行时重新装配只解决注册表一致性）。</p>
 */
@Repository
public class JdbcChannelAdministrationStore implements ChannelAdministrationStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcChannelAdministrationStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean existsByExternalAccount(String provider, String externalAccountKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_account "
                + "WHERE provider=? AND external_account_key=?", Integer.class, provider, externalAccountKey);
        return count != null && count > 0;
    }

    @Override
    public void createAccount(ChannelAccountBinding binding) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO platform_channel_account(binding_id,tenant_id,provider,external_account_key,"
                        + "credential_ref,default_employee_id,dm_scope,enabled,revision,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                binding.bindingId(), binding.tenantId().value(), binding.provider(),
                binding.externalAccountKey(), binding.credentialRef(), binding.defaultEmployeeId(),
                binding.sessionScope().name(), binding.enabled(), binding.revision(), now, now);
    }

    @Override
    public void updateAccount(ChannelAccountBinding binding) {
        jdbc.update("UPDATE platform_channel_account SET default_employee_id=?,dm_scope=?,enabled=?,"
                        + "revision=revision+1,updated_at=? "
                        + "WHERE binding_id=?",
                binding.defaultEmployeeId(), binding.sessionScope().name(), binding.enabled(),
                Timestamp.from(clock.instant()), binding.bindingId());
    }

    @Override
    public boolean updateAccountIfRevision(ChannelAccountBinding binding, long expectedRevision) {
        int updated = jdbc.update("UPDATE platform_channel_account SET default_employee_id=?,dm_scope=?,enabled=?,"
                        + "revision=revision+1,updated_at=? WHERE binding_id=? AND revision=?",
                binding.defaultEmployeeId(), binding.sessionScope().name(), binding.enabled(),
                Timestamp.from(clock.instant()), binding.bindingId(), expectedRevision);
        return updated == 1;
    }

    @Override
    public List<ChannelIdentityBinding> findIdentities(String bindingId) {
        return jdbc.query("SELECT binding_id,external_user_id,user_id,state,linked_at,updated_at "
                        + "FROM platform_channel_identity WHERE binding_id=? ORDER BY external_user_id",
                (rs, row) -> mapIdentity(rs), bindingId);
    }

    @Override
    public Optional<ChannelIdentityBinding> findIdentity(String bindingId, String externalUserId) {
        return jdbc.query("SELECT binding_id,external_user_id,user_id,state,linked_at,updated_at "
                        + "FROM platform_channel_identity WHERE binding_id=? AND external_user_id=?",
                (rs, row) -> mapIdentity(rs), bindingId, externalUserId).stream().findFirst();
    }

    /**
     * 先更新后插入，避免依赖 {@code ON DUPLICATE KEY UPDATE} 这类方言。
     * 并发重复绑定会因主键冲突而硬失败，而不是留下两条互相矛盾的关联。
     */
    @Override
    public void linkIdentity(ChannelIdentityBinding identity) {
        int updated = jdbc.update("UPDATE platform_channel_identity SET user_id=?,state=?,updated_at=? "
                        + "WHERE binding_id=? AND external_user_id=?",
                identity.userId().value(), identity.state().name(), Timestamp.from(identity.updatedAt()),
                identity.bindingId(), identity.externalUserId());
        if (updated > 0) {
            return;
        }
        jdbc.update("INSERT INTO platform_channel_identity(binding_id,external_user_id,user_id,state,"
                        + "linked_at,updated_at) VALUES(?,?,?,?,?,?)",
                identity.bindingId(), identity.externalUserId(), identity.userId().value(),
                identity.state().name(), Timestamp.from(identity.linkedAt()), Timestamp.from(identity.updatedAt()));
    }

    /** 解绑保留行并置 REVOKED；重复解绑不报错，且不会改变首次解绑时间。 */
    @Override
    public void revokeIdentity(String bindingId, String externalUserId, java.time.Instant now) {
        jdbc.update("UPDATE platform_channel_identity SET state=?,updated_at=? "
                        + "WHERE binding_id=? AND external_user_id=? AND state<>?",
                ChannelIdentityState.REVOKED.name(), Timestamp.from(now), bindingId, externalUserId,
                ChannelIdentityState.REVOKED.name());
    }

    private static ChannelIdentityBinding mapIdentity(ResultSet rs) throws SQLException {
        return new ChannelIdentityBinding(rs.getString("binding_id"), rs.getString("external_user_id"),
                new UserId(rs.getLong("user_id")), ChannelIdentityState.valueOf(rs.getString("state")),
                rs.getTimestamp("linked_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
