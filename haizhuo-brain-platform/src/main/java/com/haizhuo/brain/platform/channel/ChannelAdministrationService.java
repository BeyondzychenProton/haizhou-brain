package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.tool.ToolExecutionUserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 渠道绑定数据的管理服务：账号绑定与身份绑定的唯一写入入口。
 *
 * <p>两条硬约束：</p>
 * <ul>
 *   <li>凭据只以引用（{@code credentialRef}）形式存取，任何返回值都不含明文；</li>
 *   <li>写入后立即让渠道运行时重新装配，使注册表与持久化配置一致。</li>
 * </ul>
 *
 * <p>需要区分两件事：<b>绑定数据写入即生效</b>——入站链路每次都实时查库；
 * <b>运行时装配只影响注册表与配置一致性</b>，因此装配失败不回滚也不上抛。</p>
 *
 * <p>操作者标识已贯穿所有写方法，但平台层按既有约定不直接依赖日志实现，
 * 因此审计留痕需要后续接入审计端口后才能落地。</p>
 */
public class ChannelAdministrationService {

    private static final int MAX_BINDING_ID = 64;
    private static final int MAX_PROVIDER = 32;
    private static final int MAX_EXTERNAL_ACCOUNT_KEY = 128;
    private static final int MAX_EXTERNAL_USER_ID = 128;
    private static final int MAX_CREDENTIAL_REF = 256;

    private final ChannelAdministrationStore store;
    private final ChannelAccountDirectory directory;
    private final EmployeeCatalog employees;
    private final ToolExecutionUserDirectory users;
    private final ChannelRuntimeAdmin runtime;
    private final Clock clock;

    public ChannelAdministrationService(ChannelAdministrationStore store, ChannelAccountDirectory directory,
                                        EmployeeCatalog employees, ToolExecutionUserDirectory users,
                                        ChannelRuntimeAdmin runtime, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.directory = Objects.requireNonNull(directory);
        this.employees = Objects.requireNonNull(employees);
        this.users = Objects.requireNonNull(users);
        this.runtime = runtime == null ? ChannelRuntimeAdmin.NOOP : runtime;
        this.clock = Objects.requireNonNull(clock);
    }

    /** 全量绑定，含已禁用——管理面需要看到停用的绑定才能重新启用。 */
    public List<ChannelAccountBinding> accounts() {
        return directory.findAll();
    }

    public ChannelAccountBinding account(String bindingId) {
        return requireAccount(bindingId);
    }

    public ChannelAccountBinding createAccount(UserId actor, CreateAccount command) {
        String bindingId = required(command.bindingId(), MAX_BINDING_ID, "bindingId");
        String provider = required(command.provider(), MAX_PROVIDER, "provider");
        String externalAccountKey = required(command.externalAccountKey(), MAX_EXTERNAL_ACCOUNT_KEY,
                "externalAccountKey");
        String credentialRef = required(command.credentialRef(), MAX_CREDENTIAL_REF, "credentialRef");
        TenantId tenantId = new TenantId(positive(command.tenantId(), "tenantId"));
        requirePublishedEmployee(tenantId, command.defaultEmployeeId());
        if (store.existsByExternalAccount(provider, externalAccountKey)) {
            throw new IllegalArgumentException(
                    "Channel account already exists for provider " + provider + " and that account key");
        }

        SessionScope scope = command.sessionScope() == null ? SessionScope.defaultScope() : command.sessionScope();
        boolean enabled = command.enabled() == null || command.enabled();
        ChannelAccountBinding binding = new ChannelAccountBinding(bindingId, tenantId, provider,
                externalAccountKey, credentialRef, command.defaultEmployeeId(), scope, enabled);
        store.createAccount(binding);
        refreshRuntime();
        return binding;
    }

    /** PATCH 语义：{@code null} 表示该字段不变。 */
    public ChannelAccountBinding updateAccount(UserId actor, String bindingId, UpdateAccount command) {
        ChannelAccountBinding current = requireAccount(bindingId);
        boolean enabled = command.enabled() == null ? current.enabled() : command.enabled();
        long employeeId = command.defaultEmployeeId() == null
                ? current.defaultEmployeeId() : command.defaultEmployeeId();
        SessionScope scope = command.sessionScope() == null ? current.sessionScope() : command.sessionScope();
        if (command.defaultEmployeeId() != null) {
            requirePublishedEmployee(current.tenantId(), employeeId);
        }

        ChannelAccountBinding updated = new ChannelAccountBinding(current.bindingId(), current.tenantId(),
                current.provider(), current.externalAccountKey(), current.credentialRef(), employeeId,
                scope, enabled);
        store.updateAccount(updated);
        refreshRuntime();
        return updated;
    }

    public List<ChannelIdentityBinding> identities(String bindingId) {
        requireAccount(bindingId);
        return store.findIdentities(bindingId);
    }

    public ChannelIdentityBinding linkIdentity(UserId actor, String bindingId, String externalUserId, long userId) {
        ChannelAccountBinding account = requireAccount(bindingId);
        if (!account.enabled()) {
            // 停用渠道上再挂身份没有意义，而且会掩盖"为什么没人回复"的真实原因。
            throw new IllegalStateException("Channel account is disabled: " + bindingId);
        }
        String external = required(externalUserId, MAX_EXTERNAL_USER_ID, "externalUserId");
        long target = positive(userId, "userId");
        if (!users.isUserActive(target)) {
            // 把外部用户绑到已停用的平台账号，等于让入站消息进入无人处理的账号。
            throw new IllegalArgumentException("Target user is not active: " + target);
        }

        Instant now = clock.instant();
        ChannelIdentityBinding existing = store.findIdentity(bindingId, external).orElse(null);
        ChannelIdentityBinding linked = new ChannelIdentityBinding(bindingId, external, new UserId(target),
                ChannelIdentityState.LINKED, existing == null ? now : existing.linkedAt(), now);
        store.linkIdentity(linked);
        return linked;
    }

    /** 幂等：解绑不存在或已解绑的外部用户不报错，重复调用不会改变结果。 */
    public void revokeIdentity(UserId actor, String bindingId, String externalUserId) {
        requireAccount(bindingId);
        String external = required(externalUserId, MAX_EXTERNAL_USER_ID, "externalUserId");
        store.revokeIdentity(bindingId, external, clock.instant());
    }

    /** 渠道运行时的当前装载情况，供管理面确认"配置是否已生效到框架注册表"。 */
    public List<ChannelRuntimeAdmin.ChannelRuntimeStatus> runtimeChannels() {
        return runtime.channels();
    }

    private ChannelAccountBinding requireAccount(String bindingId) {
        String id = required(bindingId, MAX_BINDING_ID, "bindingId");
        return directory.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Channel account not found: " + id));
    }

    private void requirePublishedEmployee(TenantId tenantId, long employeeId) {
        long id = positive(employeeId, "defaultEmployeeId");
        if (employees.findPublished(tenantId, id).isEmpty()) {
            throw new IllegalArgumentException("Employee is not published: " + id);
        }
    }

    private void refreshRuntime() {
        try {
            runtime.refreshAll();
        } catch (RuntimeException ignored) {
            // 绑定数据已落库并立即对入站生效，注册表未刷新只是短暂不一致，
            // 重试或重启即可收敛，因此这里不把装配问题升级成管理写失败。
        }
    }

    private static String required(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return trimmed;
    }

    private static long positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    /** 创建管理侧绑定；凭据只接受引用，接口不接收明文。 */
    public record CreateAccount(String bindingId, long tenantId, String provider, String externalAccountKey,
                                String credentialRef, long defaultEmployeeId, SessionScope sessionScope,
                                Boolean enabled) {
    }

    public record UpdateAccount(Boolean enabled, Long defaultEmployeeId, SessionScope sessionScope) {
    }
}
