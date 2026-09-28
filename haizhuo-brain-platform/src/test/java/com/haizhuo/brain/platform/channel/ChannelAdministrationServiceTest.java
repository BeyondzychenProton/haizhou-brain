package com.haizhuo.brain.platform.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.AgentDefinitionVersion;
import com.haizhuo.brain.platform.employee.DigitalEmployee;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import com.haizhuo.brain.platform.tool.ToolExecutionUserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 渠道绑定管理服务的校验、幂等与运行时刷新行为。 */
class ChannelAdministrationServiceTest {

    private static final TenantId TENANT = new TenantId(7);
    private static final UserId ACTOR = new UserId(1);
    private static final long PUBLISHED_EMPLOYEE = 11L;
    private static final long ACTIVE_USER = 23L;
    private static final Instant T0 = Instant.parse("2026-09-28T06:00:00Z");

    @Test
    void secondBindingForTheSameProviderAccountIsRejected() {
        FakeStore store = new FakeStore();
        ChannelAdministrationService service = service(store, new AtomicInteger());

        service.createAccount(ACTOR, create("feishu-main", "feishu", "cli_app", PUBLISHED_EMPLOYEE));

        // 允许第二条会让入站无法判定归属，因此必须在写入前拒绝，而不是靠数据库唯一索引兜底。
        assertThrows(IllegalArgumentException.class, () -> service
                .createAccount(ACTOR, create("feishu-second", "feishu", "cli_app", PUBLISHED_EMPLOYEE)));
        assertEquals(1, store.accounts.size());
    }

    @Test
    void unpublishedEmployeeCannotBeBound() {
        ChannelAdministrationService service = service(new FakeStore(), new AtomicInteger());

        assertThrows(IllegalArgumentException.class,
                () -> service.createAccount(ACTOR, create("feishu-main", "feishu", "cli_app", 99L)));
    }

    @Test
    void updateKeepsFieldsThatWereNotProvided() {
        FakeStore store = new FakeStore();
        ChannelAdministrationService service = service(store, new AtomicInteger());
        service.createAccount(ACTOR, create("feishu-main", "feishu", "cli_app", PUBLISHED_EMPLOYEE));

        ChannelAccountBinding updated = service.updateAccount(ACTOR, "feishu-main",
                new ChannelAdministrationService.UpdateAccount(false, null, null));

        assertTrue(!updated.enabled(), "停用必须生效");
        assertEquals(PUBLISHED_EMPLOYEE, updated.defaultEmployeeId(), "未提供的字段不得被清空");
        assertEquals(SessionScope.PER_PEER, updated.sessionScope(), "未提供的会话粒度必须保持原值");
    }

    @Test
    void identityLinkRequiresActiveUserAndEnabledAccount() {
        FakeStore store = new FakeStore();
        ChannelAdministrationService service = service(store, new AtomicInteger());
        service.createAccount(ACTOR, create("feishu-main", "feishu", "cli_app", PUBLISHED_EMPLOYEE));

        assertThrows(IllegalArgumentException.class,
                () -> service.linkIdentity(ACTOR, "feishu-main", "ou-1", 99L), "停用用户不得被绑定");

        ChannelIdentityBinding linked = service.linkIdentity(ACTOR, "feishu-main", "ou-1", ACTIVE_USER);
        assertEquals(ChannelIdentityState.LINKED, linked.state());

        service.updateAccount(ACTOR, "feishu-main", new ChannelAdministrationService.UpdateAccount(false, null, null));
        assertThrows(IllegalStateException.class,
                () -> service.linkIdentity(ACTOR, "feishu-main", "ou-2", ACTIVE_USER), "停用渠道上不得再挂身份");
    }

    @Test
    void revokingIsIdempotentAndKeepsTheRowForAudit() {
        FakeStore store = new FakeStore();
        ChannelAdministrationService service = service(store, new AtomicInteger());
        service.createAccount(ACTOR, create("feishu-main", "feishu", "cli_app", PUBLISHED_EMPLOYEE));
        service.linkIdentity(ACTOR, "feishu-main", "ou-1", ACTIVE_USER);

        service.revokeIdentity(ACTOR, "feishu-main", "ou-1");
        service.revokeIdentity(ACTOR, "feishu-main", "ou-1");
        service.revokeIdentity(ACTOR, "feishu-main", "never-linked");

        assertEquals(1, store.identities.size(), "解绑必须保留行而不是删除，否则审计与旧投递抑制都会失效");
        assertEquals(ChannelIdentityState.REVOKED,
                store.identities.values().iterator().next().state());
    }

    @Test
    void bindingChangesRefreshTheRuntimeButIdentityChangesDoNot() {
        AtomicInteger refreshes = new AtomicInteger();
        ChannelAdministrationService service = service(new FakeStore(), refreshes);

        service.createAccount(ACTOR, create("feishu-main", "feishu", "cli_app", PUBLISHED_EMPLOYEE));
        assertEquals(1, refreshes.get(), "新建绑定必须让渠道运行时重新装配");

        service.linkIdentity(ACTOR, "feishu-main", "ou-1", ACTIVE_USER);
        service.revokeIdentity(ACTOR, "feishu-main", "ou-1");
        assertEquals(1, refreshes.get(), "身份绑定不改变渠道装载，因此不应触发装配");

        service.updateAccount(ACTOR, "feishu-main",
                new ChannelAdministrationService.UpdateAccount(null, null, SessionScope.PER_CHANNEL_PEER));
        assertEquals(2, refreshes.get(), "改会话粒度必须让装配重新投影配置");
    }

    private static ChannelAdministrationService service(FakeStore store, AtomicInteger refreshes) {
        ChannelAccountDirectory directory = new ChannelAccountDirectory() {
            @Override
            public Optional<ChannelAccountBinding> findById(String bindingId) {
                return Optional.ofNullable(store.accounts.get(bindingId));
            }

            @Override
            public List<ChannelAccountBinding> findAll() {
                return List.copyOf(store.accounts.values());
            }
        };
        EmployeeCatalog employees = (tenant, employeeId) -> employeeId == PUBLISHED_EMPLOYEE
                ? Optional.of(published(tenant)) : Optional.empty();
        ToolExecutionUserDirectory users = userId -> userId == ACTIVE_USER;
        ChannelRuntimeAdmin runtime = new ChannelRuntimeAdmin() {
            @Override
            public List<ChannelRuntimeStatus> channels() {
                return List.of();
            }

            @Override
            public void refreshAll() {
                refreshes.incrementAndGet();
            }
        };
        return new ChannelAdministrationService(store, directory, employees, users, runtime,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static ChannelAdministrationService.CreateAccount create(String bindingId, String provider,
                                                                    String accountKey, long employeeId) {
        return new ChannelAdministrationService.CreateAccount(bindingId, TENANT.value(), provider, accountKey,
                "env:" + bindingId, employeeId, SessionScope.PER_PEER, true);
    }

    private static PublishedEmployee published(TenantId tenantId) {
        return new PublishedEmployee(new DigitalEmployee(PUBLISHED_EMPLOYEE, tenantId, "assistant", "助手", true),
                new AgentDefinitionVersion(91, PUBLISHED_EMPLOYEE, 1, "instructions", "dashscope", "qwen-plus", T0),
                List.of());
    }

    /** 内存写侧：够用即可，重点在于把服务的校验与刷新语义钉住。 */
    private static final class FakeStore implements ChannelAdministrationStore {

        private final Map<String, ChannelAccountBinding> accounts = new LinkedHashMap<>();
        private final Map<String, ChannelIdentityBinding> identities = new LinkedHashMap<>();

        @Override
        public boolean existsByExternalAccount(String provider, String externalAccountKey) {
            return accounts.values().stream().anyMatch(binding -> binding.provider().equals(provider)
                    && binding.externalAccountKey().equals(externalAccountKey));
        }

        @Override
        public void createAccount(ChannelAccountBinding binding) {
            accounts.put(binding.bindingId(), binding);
        }

        @Override
        public void updateAccount(ChannelAccountBinding binding) {
            accounts.put(binding.bindingId(), binding);
        }

        @Override
        public List<ChannelIdentityBinding> findIdentities(String bindingId) {
            return identities.values().stream().filter(identity -> identity.bindingId().equals(bindingId)).toList();
        }

        @Override
        public Optional<ChannelIdentityBinding> findIdentity(String bindingId, String externalUserId) {
            return Optional.ofNullable(identities.get(key(bindingId, externalUserId)));
        }

        @Override
        public void linkIdentity(ChannelIdentityBinding identity) {
            identities.put(key(identity.bindingId(), identity.externalUserId()), identity);
        }

        @Override
        public void revokeIdentity(String bindingId, String externalUserId, Instant now) {
            ChannelIdentityBinding existing = identities.get(key(bindingId, externalUserId));
            if (existing == null) {
                return;
            }
            identities.put(key(bindingId, externalUserId), new ChannelIdentityBinding(bindingId, externalUserId,
                    existing.userId(), ChannelIdentityState.REVOKED, existing.linkedAt(), now));
        }

        private static String key(String bindingId, String externalUserId) {
            return bindingId + "|" + externalUserId;
        }
    }
}
