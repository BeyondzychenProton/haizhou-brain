package com.haizhuo.brain.api.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAccountChangedException;
import com.haizhuo.brain.platform.channel.ChannelAdministrationService;
import com.haizhuo.brain.platform.channel.ChannelAdministrationStore;
import com.haizhuo.brain.platform.channel.ChannelIdentityBinding;
import com.haizhuo.brain.platform.channel.ChannelIdentityState;
import com.haizhuo.brain.platform.channel.ChannelManagementCommandExecutor;
import com.haizhuo.brain.platform.channel.ChannelRuntimeAdmin;
import com.haizhuo.brain.platform.channel.ChannelRuntimeAdmin.ChannelRuntimeStatus;
import com.haizhuo.brain.platform.channel.SessionScope;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 管理端点的请求映射与响应形状：服务语义不在这一层重复验证。 */
class ChannelAdminControllerTest {

    private static final UserId ACTOR_ID = new UserId(1);
    private static final AuthenticatedUser ACTOR = new AuthenticatedUser(
            ACTOR_ID, Set.of(PlatformRole.PLATFORM_ADMIN), 1, false);
    private static final TenantId TENANT = new TenantId(7);
    private static final Instant T0 = Instant.parse("2026-09-28T06:00:00Z");
    private static final ChannelAccountBinding BINDING = new ChannelAccountBinding("sim-account", TENANT,
            "simulated", "sim-key", "env:sim", 2L, SessionScope.PER_PEER, true);

    @Test
    void createMapsRequestToCommandAndReturnsOnlyCredentialReference() {
        FakeAdministration administration = new FakeAdministration();
        ChannelAdminController controller = new ChannelAdminController(administration);

        ChannelAdminController.AccountResponse response = controller.createAccount(ACTOR,
                new ChannelAdminController.CreateAccountRequest("sim-account", 7L, "simulated", "sim-key",
                        "env:sim", 2L, SessionScope.PER_PEER, true)).block();

        assertEquals("env:sim", response.credentialRef());
        assertEquals("PER_PEER", response.sessionScope());
        assertEquals(ACTOR_ID, administration.lastActor);
        assertEquals(new ChannelAdministrationService.CreateAccount("sim-account", 7L, "simulated", "sim-key",
                "env:sim", 2L, SessionScope.PER_PEER, true), administration.lastCreate);
    }

    @Test
    void newAccountCommandCarriesReasonAndReturnsRevisionAndRefreshEvidence() {
        FakeAdministration administration = new FakeAdministration();
        administration.refresh = new ChannelAdministrationService.RuntimeRefresh(
                "APPLIED", 1, 1L, "channel-runtime-test", T0, null);
        ChannelAdminController controller = new ChannelAdminController(administration);

        ChannelAdminController.AccountResponse response = controller.createAccount(ACTOR,
                new ChannelAdminController.CreateAccountRequest("sim-account", 7L, "simulated", "sim-key",
                        "env:sim", 2L, SessionScope.PER_PEER, true, "req-1", "供应商账号已核准")).block();

        assertEquals(1L, response.revision());
        assertEquals("APPLIED", response.runtimeRefresh().status());
        assertEquals("req-1", administration.commands.last.requestId());
        assertEquals("供应商账号已核准", administration.commands.last.reason());
        assertEquals("ADMIN_UI", administration.commands.last.clientSource());
        assertEquals("provider=simulated,defaultEmployeeId=2,sessionScope=PER_PEER,enabled=true",
                administration.commands.last.safeChanges());
        assertTrue(!administration.commands.last.safeChanges().contains("env:sim"),
                "审计摘要不得记录凭据引用或账号私密标识");
    }

    @Test
    void successfulResponseHasNoFieldCarryingAPlaintextSecret() {
        ChannelAdminController.AccountResponse response = account();

        // 响应里只允许出现引用名；一旦有人加入明文凭据字段，这个断言会立刻失败。
        assertEquals(10, ChannelAdminController.AccountResponse.class.getRecordComponents().length,
                "响应形状变更需重新确认没有引入明文凭据字段");
        assertEquals("env:sim", response.credentialRef());
    }

    @Test
    void updatePassesUnspecifiedFieldsAsNullSoServiceKeepsThem() {
        FakeAdministration administration = new FakeAdministration();
        ChannelAdminController controller = new ChannelAdminController(administration);

        controller.updateAccount(ACTOR, "sim-account",
                new ChannelAdminController.UpdateAccountRequest(false, null, null)).block();

        assertEquals(ACTOR_ID, administration.lastActor);
        assertEquals("sim-account", administration.lastBindingId);
        assertEquals(new ChannelAdministrationService.UpdateAccount(false, null, null), administration.lastUpdate);
        assertNull(administration.lastExpectedRevision);
    }

    @Test
    void newUpdateCommandCarriesExpectedRevisionAndAuditMetadata() {
        FakeAdministration administration = new FakeAdministration();
        administration.updatedBinding = new ChannelAccountBinding("sim-account", TENANT, "simulated",
                "sim-key", "env:sim", 2L, SessionScope.PER_PEER, false, 2L);
        administration.refresh = new ChannelAdministrationService.RuntimeRefresh(
                "APPLIED", 2, 2L, "channel-runtime-test", T0, null);
        ChannelAdminController controller = new ChannelAdminController(administration);

        ChannelAdminController.AccountResponse response = controller.updateAccount(ACTOR, "sim-account",
                new ChannelAdminController.UpdateAccountRequest(false, null, null, 1L,
                        "update-1", "停用已撤销账号")).block();

        assertEquals(2L, response.revision());
        assertEquals(2L, response.runtimeRefresh().loadedRevision());
        assertEquals(ACTOR_ID, administration.lastActor);
        assertEquals("sim-account", administration.lastBindingId);
        assertEquals(new ChannelAdministrationService.UpdateAccount(false, null, null), administration.lastUpdate);
        assertEquals(1L, administration.lastExpectedRevision);
        assertEquals("update-1", administration.commands.last.requestId());
        assertEquals("停用已撤销账号", administration.commands.last.reason());
    }

    @Test
    void linkAndRevokeIdentityAreBothForwardedWithTheActor() {
        FakeAdministration administration = new FakeAdministration();
        administration.identityToReturn = new ChannelIdentityBinding("sim-account", "ou-1", new UserId(23),
                ChannelIdentityState.LINKED, T0, T0);
        ChannelAdminController controller = new ChannelAdminController(administration);

        ChannelAdminController.IdentityResponse response = controller.linkIdentity(ACTOR, "sim-account", "ou-1",
                new ChannelAdminController.LinkIdentityRequest(23L)).block();
        assertEquals("LINKED", response.state());
        assertEquals(23L, response.userId());
        assertEquals(ACTOR_ID, administration.lastActor);
        assertEquals("sim-account", administration.lastBindingId);
        assertEquals("ou-1", administration.lastExternalUserId);

        controller.revokeIdentity(ACTOR, "sim-account", "ou-1").block();
        assertEquals(ACTOR_ID, administration.lastActor);
        assertEquals("sim-account", administration.lastBindingId);
        assertEquals("ou-1", administration.lastExternalUserId);
    }

    @Test
    void newIdentityRevokeCommandCarriesReasonAndIdempotencyKey() {
        FakeAdministration administration = new FakeAdministration();
        ChannelAdminController controller = new ChannelAdminController(administration);

        ChannelAdminController.RevokeIdentityResponse response = controller.revokeIdentityCommand(ACTOR,
                "sim-account", "ou-2",
                new ChannelAdminController.RevokeIdentityRequest("revoke-1", "员工离职后解除渠道映射")).block();

        assertEquals("REVOKED", response.state());
        assertEquals(ACTOR_ID, administration.lastActor);
        assertEquals("sim-account", administration.lastBindingId);
        assertEquals("ou-2", administration.lastExternalUserId);
        assertEquals("revoke-1", administration.commands.last.requestId());
        assertEquals("员工离职后解除渠道映射", administration.commands.last.reason());
        assertEquals("ADMIN_UI", administration.commands.last.clientSource());
    }

    @Test
    void staleAccountRevisionMapsToTypedConflictResponse() {
        ChannelAdminController controller = new ChannelAdminController(new FakeAdministration());

        var response = controller.handleAccountChanged(new ChannelAccountChangedException()).block();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("CHANNEL_ACCOUNT_CHANGED", response.getBody().code());
    }

    @Test
    void listAccountsExposesDisabledBindingsAsWell() {
        FakeAdministration administration = new FakeAdministration();
        ChannelAccountBinding disabled = new ChannelAccountBinding("sim-old", TENANT, "simulated", "old-key",
                "env:old", 2L, SessionScope.MAIN, false);
        administration.accountBindings = List.of(BINDING, disabled);
        ChannelAdminController controller = new ChannelAdminController(administration);

        List<ChannelAdminController.AccountResponse> accounts = controller.listAccounts().block();

        assertEquals(2, accounts.size(), "停用的绑定也必须在管理面可见，否则无法重新启用");
        assertEquals("MAIN", accounts.get(1).sessionScope());
    }

    @Test
    void runtimeViewProjectsWhatTheFrameworkRegistryActuallyHolds() {
        FakeAdministration administration = new FakeAdministration();
        administration.runtimeStatuses = List.of(
                new ChannelRuntimeStatus("simulated", "employee-2", "PER_PEER", 1, true));
        ChannelAdminController controller = new ChannelAdminController(administration);

        List<ChannelAdminController.RuntimeChannelResponse> channels = controller.runtimeChannels().block();

        assertEquals(1, channels.size());
        assertEquals("simulated", channels.get(0).channelId());
        assertEquals("employee-2", channels.get(0).defaultAgentId());
        assertEquals(1, channels.get(0).bindingCount());
        assertTrue(channels.get(0).started());
    }

    private static ChannelAdminController.AccountResponse account() {
        return new ChannelAdminController.AccountResponse(BINDING.bindingId(), BINDING.tenantId().value(),
                BINDING.provider(), BINDING.externalAccountKey(), BINDING.credentialRef(),
                BINDING.defaultEmployeeId(), BINDING.sessionScope().name(), BINDING.enabled());
    }

    /** 控制器边界替身；平台服务策略由 ChannelAdministrationServiceTest 覆盖。 */
    private static final class FakeAdministration extends ChannelAdministrationService {
        private final RecordingCommandExecutor commands;
        private ChannelAccountBinding createdBinding = BINDING;
        private ChannelAccountBinding updatedBinding = BINDING;
        private ChannelAdministrationService.RuntimeRefresh refresh;
        private List<ChannelAccountBinding> accountBindings = List.of();
        private List<ChannelRuntimeStatus> runtimeStatuses = List.of();
        private ChannelIdentityBinding identityToReturn;
        private UserId lastActor;
        private ChannelAdministrationService.CreateAccount lastCreate;
        private ChannelAdministrationService.UpdateAccount lastUpdate;
        private Long lastExpectedRevision;
        private String lastBindingId;
        private String lastExternalUserId;

        private FakeAdministration() {
            this(new RecordingCommandExecutor());
        }

        private FakeAdministration(RecordingCommandExecutor commands) {
            super(EMPTY_STORE, bindingId -> Optional.empty(), (tenantId, employeeId) -> Optional.empty(),
                    userId -> true, ChannelRuntimeAdmin.NOOP, commands, Clock.fixed(T0, ZoneOffset.UTC));
            this.commands = commands;
        }

        @Override
        public ChannelAccountBinding createAccountForCommand(UserId actor, CreateAccount command) {
            lastActor = actor;
            lastCreate = command;
            return createdBinding;
        }

        @Override
        public ChannelAccountBinding updateAccountForCommand(UserId actor, String bindingId, UpdateAccount command,
                                                              Long expectedRevision) {
            lastActor = actor;
            lastBindingId = bindingId;
            lastUpdate = command;
            lastExpectedRevision = expectedRevision;
            return updatedBinding;
        }

        @Override
        public RuntimeRefresh refreshAfterMutation(ChannelAccountBinding binding) {
            return refresh;
        }

        @Override
        public ChannelIdentityBinding linkIdentity(UserId actor, String bindingId, String externalUserId,
                                                   long userId) {
            lastActor = actor;
            lastBindingId = bindingId;
            lastExternalUserId = externalUserId;
            return identityToReturn;
        }

        @Override
        public void revokeIdentity(UserId actor, String bindingId, String externalUserId) {
            lastActor = actor;
            lastBindingId = bindingId;
            lastExternalUserId = externalUserId;
        }

        @Override
        public List<ChannelAccountBinding> accounts() {
            return accountBindings;
        }

        @Override
        public List<ChannelRuntimeStatus> runtimeChannels() {
            return runtimeStatuses;
        }
    }

    private static final class RecordingCommandExecutor implements ChannelManagementCommandExecutor {
        private Command last;

        @Override
        public <T> T execute(Command command, Class<T> responseType, Supplier<T> action) {
            last = command;
            return responseType.cast(action.get());
        }
    }

    private static final ChannelAdministrationStore EMPTY_STORE = new ChannelAdministrationStore() {
        @Override
        public boolean existsByExternalAccount(String provider, String externalAccountKey) {
            return false;
        }

        @Override
        public void createAccount(ChannelAccountBinding binding) {
        }

        @Override
        public void updateAccount(ChannelAccountBinding binding) {
        }

        @Override
        public List<ChannelIdentityBinding> findIdentities(String bindingId) {
            return List.of();
        }

        @Override
        public Optional<ChannelIdentityBinding> findIdentity(String bindingId, String externalUserId) {
            return Optional.empty();
        }

        @Override
        public void linkIdentity(ChannelIdentityBinding identity) {
        }

        @Override
        public void revokeIdentity(String bindingId, String externalUserId, Instant now) {
        }
    };
}
