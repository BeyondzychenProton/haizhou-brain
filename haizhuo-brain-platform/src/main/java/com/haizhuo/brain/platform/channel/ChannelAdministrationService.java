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
 * <p>新管理命令由 {@link ChannelManagementCommandExecutor} 将账号/身份变更、审计与幂等回执
 * 放入同一持久化事务；旧客户端窗口仍会记录来源为 LEGACY_CLIENT 的审计事实。</p>
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
    private final ChannelManagementCommandExecutor commands;
    private final Clock clock;

    public ChannelAdministrationService(ChannelAdministrationStore store, ChannelAccountDirectory directory,
                                        EmployeeCatalog employees, ToolExecutionUserDirectory users,
                                        ChannelRuntimeAdmin runtime, Clock clock) {
        this(store, directory, employees, users, runtime, ChannelManagementCommandExecutor.DIRECT, clock);
    }

    public ChannelAdministrationService(ChannelAdministrationStore store, ChannelAccountDirectory directory,
                                        EmployeeCatalog employees, ToolExecutionUserDirectory users,
                                        ChannelRuntimeAdmin runtime, ChannelManagementCommandExecutor commands,
                                        Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.directory = Objects.requireNonNull(directory);
        this.employees = Objects.requireNonNull(employees);
        this.users = Objects.requireNonNull(users);
        this.runtime = runtime == null ? ChannelRuntimeAdmin.NOOP : runtime;
        this.commands = commands == null ? ChannelManagementCommandExecutor.DIRECT : commands;
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
        ChannelAccountBinding binding = createAccountState(actor, command);
        refreshRuntime();
        return binding;
    }

    /** 在命令事务中持久化账号；事务提交后由 API 刷新运行时。 */
    public ChannelAccountBinding createAccountForCommand(UserId actor, CreateAccount command) {
        return createAccountState(actor, command);
    }

    private ChannelAccountBinding createAccountState(UserId actor, CreateAccount command) {
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
                externalAccountKey, credentialRef, command.defaultEmployeeId(), scope, enabled, 1);
        store.createAccount(binding);
        return binding;
    }

    /** PATCH 语义：{@code null} 表示该字段不变。 */
    public ChannelAccountBinding updateAccount(UserId actor, String bindingId, UpdateAccount command) {
        ChannelAccountBinding binding = updateAccountState(actor, bindingId, command, null);
        refreshRuntime();
        return binding;
    }

    /** 新管理界面使用的 CAS 更新；空修订号仅在旧客户端兼容期内保留。 */
    public ChannelAccountBinding updateAccountForCommand(UserId actor, String bindingId,
                                                          UpdateAccount command, Long expectedRevision) {
        return updateAccountState(actor, bindingId, command, expectedRevision);
    }

    private ChannelAccountBinding updateAccountState(UserId actor, String bindingId,
                                                       UpdateAccount command, Long expectedRevision) {
        ChannelAccountBinding current = requireAccount(bindingId);
        if (expectedRevision != null && (expectedRevision <= 0 || current.revision() != expectedRevision)) {
            throw new ChannelAccountChangedException();
        }
        boolean enabled = command.enabled() == null ? current.enabled() : command.enabled();
        long employeeId = command.defaultEmployeeId() == null
                ? current.defaultEmployeeId() : command.defaultEmployeeId();
        SessionScope scope = command.sessionScope() == null ? current.sessionScope() : command.sessionScope();
        if (command.defaultEmployeeId() != null) {
            requirePublishedEmployee(current.tenantId(), employeeId);
        }

        ChannelAccountBinding updated = new ChannelAccountBinding(current.bindingId(), current.tenantId(),
                current.provider(), current.externalAccountKey(), current.credentialRef(), employeeId,
                scope, enabled, current.revision() + 1);
        if (expectedRevision == null) {
            store.updateAccount(updated);
        } else if (!store.updateAccountIfRevision(updated, expectedRevision)) {
            throw new ChannelAccountChangedException();
        }
        return updated;
    }

    /** 在持久化适配器的事务中执行命令并写入审计/回执。 */
    public <T> T executeManagementCommand(ChannelManagementCommandExecutor.Command command,
                                         Class<T> responseType, java.util.function.Supplier<T> action) {
        return commands.execute(command, responseType, action);
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

    public RuntimeRefresh refreshAfterMutation(ChannelAccountBinding binding) {
        try {
            runtime.refreshAll();
            return runtimeRefresh(binding);
        } catch (RuntimeException failure) {
            return new RuntimeRefresh("FAILED", binding.revision(), null,
                    safeInstanceLabel(), clock.instant(), "RUNTIME_REFRESH_FAILED");
        }
    }

    private String safeInstanceLabel() {
        try {
            return runtime.instanceLabel();
        } catch (RuntimeException ignored) {
            return "UNKNOWN";
        }
    }

    /** 读取当前进程内的观测结果，不宣称集群状态已收敛。 */
    public RuntimeRefresh runtimeRefresh(ChannelAccountBinding binding) {
        ChannelRuntimeAdmin.AccountLoadSnapshot snapshot = runtime.accountSnapshots().stream()
                .filter(candidate -> candidate.bindingId().equals(binding.bindingId())
                        && candidate.configuredRevision() == binding.revision())
                .findFirst().orElse(null);
        Long loaded = snapshot == null ? loadedRevision(binding.bindingId()) : snapshot.loadedRevision();
        boolean applied = binding.enabled()
                ? snapshot != null && "LOADED".equals(snapshot.loadState())
                    && Objects.equals(snapshot.loadedRevision(), binding.revision())
                : snapshot != null && "UNLOADED".equals(snapshot.loadState());
        return new RuntimeRefresh(applied ? "APPLIED" : "PENDING", binding.revision(), loaded,
                runtime.instanceLabel(), clock.instant(), null);
    }

    private Long loadedRevision(String bindingId) {
        return runtime.accountSnapshots().stream()
                .filter(candidate -> candidate.bindingId().equals(bindingId))
                .map(ChannelRuntimeAdmin.AccountLoadSnapshot::loadedRevision)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
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

    public record RuntimeRefresh(String status, long requestedRevision, Long loadedRevision,
                                 String instanceLabel, Instant observedAt, String safeErrorCode) {
    }
}
