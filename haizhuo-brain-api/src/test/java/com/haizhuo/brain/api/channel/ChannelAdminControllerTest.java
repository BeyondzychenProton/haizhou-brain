package com.haizhuo.brain.api.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAdministrationService;
import com.haizhuo.brain.platform.channel.ChannelIdentityBinding;
import com.haizhuo.brain.platform.channel.ChannelIdentityState;
import com.haizhuo.brain.platform.channel.ChannelRuntimeAdmin.ChannelRuntimeStatus;
import com.haizhuo.brain.platform.channel.SessionScope;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 管理端点的请求映射与响应形状：服务语义不在这一层重复验证。 */
@ExtendWith(MockitoExtension.class)
class ChannelAdminControllerTest {

    private static final UserId ACTOR = new UserId(1);
    private static final TenantId TENANT = new TenantId(7);
    private static final Instant T0 = Instant.parse("2026-09-28T06:00:00Z");
    private static final ChannelAccountBinding BINDING = new ChannelAccountBinding("sim-account", TENANT,
            "simulated", "sim-key", "env:sim", 2L, SessionScope.PER_PEER, true);

    @Mock ChannelAdministrationService administration;
    @Mock AuthenticatedUser actor;

    @Test
    void createMapsRequestToCommandAndReturnsOnlyCredentialReference() {
        when(actor.userId()).thenReturn(ACTOR);
        when(administration.createAccount(eq(ACTOR), any())).thenReturn(BINDING);
        ChannelAdminController controller = new ChannelAdminController(administration);

        ChannelAdminController.AccountResponse response = controller.createAccount(actor,
                new ChannelAdminController.CreateAccountRequest("sim-account", 7L, "simulated", "sim-key",
                        "env:sim", 2L, SessionScope.PER_PEER, true)).block();

        assertEquals("env:sim", response.credentialRef());
        assertEquals("PER_PEER", response.sessionScope());
        verify(administration).createAccount(ACTOR, new ChannelAdministrationService.CreateAccount("sim-account",
                7L, "simulated", "sim-key", "env:sim", 2L, SessionScope.PER_PEER, true));
    }

    @Test
    void successfulResponseHasNoFieldCarryingAPlaintextSecret() {
        ChannelAdminController.AccountResponse response = account();

        // 响应里只允许出现引用名；一旦有人加入明文凭据字段，这个断言会立刻失败。
        assertEquals(8, ChannelAdminController.AccountResponse.class.getRecordComponents().length,
                "响应形状变更需重新确认没有引入明文凭据字段");
        assertEquals("env:sim", response.credentialRef());
    }

    @Test
    void updatePassesUnspecifiedFieldsAsNullSoServiceKeepsThem() {
        when(actor.userId()).thenReturn(ACTOR);
        when(administration.updateAccount(eq(ACTOR), eq("sim-account"), any())).thenReturn(BINDING);
        ChannelAdminController controller = new ChannelAdminController(administration);

        controller.updateAccount(actor, "sim-account",
                new ChannelAdminController.UpdateAccountRequest(false, null, null)).block();

        verify(administration).updateAccount(ACTOR, "sim-account",
                new ChannelAdministrationService.UpdateAccount(false, null, null));
    }

    @Test
    void linkAndRevokeIdentityAreBothForwardedWithTheActor() {
        when(actor.userId()).thenReturn(ACTOR);
        ChannelIdentityBinding linked = new ChannelIdentityBinding("sim-account", "ou-1", new UserId(23),
                ChannelIdentityState.LINKED, T0, T0);
        when(administration.linkIdentity(ACTOR, "sim-account", "ou-1", 23L)).thenReturn(linked);
        ChannelAdminController controller = new ChannelAdminController(administration);

        ChannelAdminController.IdentityResponse response = controller.linkIdentity(actor, "sim-account", "ou-1",
                new ChannelAdminController.LinkIdentityRequest(23L)).block();
        assertEquals("LINKED", response.state());
        assertEquals(23L, response.userId());

        controller.revokeIdentity(actor, "sim-account", "ou-1").block();
        verify(administration).revokeIdentity(ACTOR, "sim-account", "ou-1");
    }

    @Test
    void listAccountsExposesDisabledBindingsAsWell() {
        ChannelAccountBinding disabled = new ChannelAccountBinding("sim-old", TENANT, "simulated", "old-key",
                "env:old", 2L, SessionScope.MAIN, false);
        when(administration.accounts()).thenReturn(List.of(BINDING, disabled));
        ChannelAdminController controller = new ChannelAdminController(administration);

        List<ChannelAdminController.AccountResponse> accounts = controller.listAccounts().block();

        assertEquals(2, accounts.size(), "停用的绑定也必须在管理面可见，否则无法重新启用");
        assertEquals("MAIN", accounts.get(1).sessionScope());
    }

    @Test
    void runtimeViewProjectsWhatTheFrameworkRegistryActuallyHolds() {
        when(administration.runtimeChannels()).thenReturn(List.of(
                new ChannelRuntimeStatus("simulated", "employee-2", "PER_PEER", 1, true)));
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
}
