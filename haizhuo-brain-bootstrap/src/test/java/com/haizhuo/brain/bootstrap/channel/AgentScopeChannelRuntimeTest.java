package com.haizhuo.brain.bootstrap.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAccountDirectory;
import com.haizhuo.brain.platform.channel.ChannelAcceptance;
import com.haizhuo.brain.platform.channel.ChannelIdentityDirectory;
import com.haizhuo.brain.platform.channel.ChannelIngressService;
import com.haizhuo.brain.platform.channel.ChannelRuntimeAdmin.ChannelRuntimeStatus;
import com.haizhuo.brain.platform.channel.ChannelTurnStore;
import com.haizhuo.brain.platform.channel.SessionScope;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 渠道运行时装配：确认平台持久化绑定被正确投影成 AgentScope 的渠道配置，
 * 并且装载 / 卸载 / 热更新三条路径都按框架语义工作。
 */
class AgentScopeChannelRuntimeTest {

    private static final TenantId TENANT = new TenantId(7);

    @Test
    void loadsOneChannelPerProviderWithProjectedRoutesAndScope() {
        FakeDirectory directory = new FakeDirectory();
        directory.add(binding("feishu-main", "feishu", "cli_app", 11L, SessionScope.PER_PEER, true));
        directory.add(binding("wecom-main", "wecom", "corp", 12L, SessionScope.PER_CHANNEL_PEER, true));
        AgentScopeChannelRuntime runtime = runtime(directory);

        runtime.refreshAll();
        List<ChannelRuntimeStatus> channels = runtime.channels();

        assertEquals(2, channels.size(), "一个 provider 对应一个渠道");
        ChannelRuntimeStatus feishu = channel(channels, "feishu");
        assertEquals("employee-11", feishu.defaultAgentId(), "渠道默认 Agent 必须指向承载它的员工");
        assertEquals("PER_PEER", feishu.sessionScope());
        assertEquals(1, feishu.bindingCount());
        assertTrue(feishu.started(), "装配后渠道必须处于已启动状态");
        assertEquals("PER_CHANNEL_PEER", channel(channels, "wecom").sessionScope());
    }

    @Test
    void disabledBindingUnloadsItsProvider() {
        FakeDirectory directory = new FakeDirectory();
        directory.add(binding("feishu-main", "feishu", "cli_app", 11L, SessionScope.PER_PEER, true));
        ChannelManager registry = new ChannelManager();
        AgentScopeChannelRuntime runtime = runtime(directory, registry);
        runtime.refreshAll();
        assertEquals(List.of("feishu"), registry.channelIds(), "启用绑定必须注册到 AgentScope 原生 registry");
        assertEquals(1, runtime.channels().size());

        directory.replace(binding("feishu-main", "feishu", "cli_app", 11L, SessionScope.PER_PEER, false));
        runtime.refreshAll();

        assertTrue(registry.channelIds().isEmpty(), "注销最后一个绑定后必须从 AgentScope 原生 registry 移除渠道");
        assertTrue(registry.getChannel("feishu").isEmpty(), "被注销渠道不得仍能从 AgentScope 原生 registry 查询到");
        ChannelRuntimeStatus unloaded = channel(runtime.channels(), "feishu");
        assertFalse(unloaded.started(), "注销最后一个绑定后不应报告渠道已启动");
        assertEquals(0, unloaded.bindingCount(), "已注销渠道不应报告运行时绑定");
        assertEquals("UNLOADED", unloaded.accountSnapshots().get(0).loadState());
        assertNull(unloaded.accountSnapshots().get(0).loadedRevision());
    }

    @Test
    void addingABindingHotUpdatesTheExistingChannel() {
        FakeDirectory directory = new FakeDirectory();
        directory.add(binding("feishu-main", "feishu", "cli_app", 11L, SessionScope.PER_PEER, true));
        AgentScopeChannelRuntime runtime = runtime(directory);
        runtime.refreshAll();
        assertEquals(1, channel(runtime.channels(), "feishu").bindingCount());

        // 同一渠道增加第二个账号：不该换实例，而应通过框架的热更新钩子更新路由表。
        directory.add(binding("feishu-second", "feishu", "cli_app_2", 11L, SessionScope.PER_PEER, true));
        runtime.refreshAll();

        assertEquals(2, channel(runtime.channels(), "feishu").bindingCount());
    }

    @Test
    void runtimeReportsOnlyRevisionsAcceptedByThisInstance() {
        FakeDirectory directory = new FakeDirectory();
        directory.add(binding("feishu-main", "feishu", "cli_app", 11L, SessionScope.PER_PEER, true, 1));
        AgentScopeChannelRuntime runtime = runtime(directory);

        assertEquals("UNKNOWN", runtime.accountSnapshots().get(0).loadState());
        runtime.refreshAll();
        assertEquals("LOADED", runtime.accountSnapshots().get(0).loadState());
        assertEquals(1L, runtime.accountSnapshots().get(0).loadedRevision());

        directory.replace(binding("feishu-main", "feishu", "cli_app", 12L, SessionScope.PER_PEER, true, 2));
        assertEquals("STALE", runtime.accountSnapshots().get(0).loadState());
        assertEquals(1L, runtime.accountSnapshots().get(0).loadedRevision());

        runtime.refreshAll();
        assertEquals("LOADED", runtime.accountSnapshots().get(0).loadState());
        assertEquals(2L, runtime.accountSnapshots().get(0).loadedRevision());

        directory.replace(binding("feishu-main", "feishu", "cli_app", 12L, SessionScope.PER_PEER, false, 3));
        runtime.refreshAll();
        assertEquals("UNLOADED", runtime.accountSnapshots().get(0).loadState());
        assertNull(runtime.accountSnapshots().get(0).loadedRevision());
    }

    @Test
    void strictestScopeWinsWhenOneChannelHasMixedScopes() {
        FakeDirectory directory = new FakeDirectory();
        directory.add(binding("feishu-main", "feishu", "cli_app", 11L, SessionScope.MAIN, true));
        directory.add(binding("feishu-second", "feishu", "cli_app_2", 11L, SessionScope.PER_ACCOUNT_CHANNEL_PEER, true));
        AgentScopeChannelRuntime runtime = runtime(directory);

        runtime.refreshAll();

        // 放宽会让不同对端共用一个会话，属不可接受的失败方向，因此取最严的一条。
        assertEquals("PER_ACCOUNT_CHANNEL_PEER", channel(runtime.channels(), "feishu").sessionScope());
    }

    @Test
    void platformChannelAcceptsRoutingConfigForItselfAndRejectsForeignChannel() {
        PlatformChannel channel = new PlatformChannel("feishu", new FakeDirectory(), ingress(), null,
                ChannelConfig.of("feishu", "employee-11"));

        ChannelConfig next = ChannelConfig.builder("feishu")
                .defaultAgentId("employee-12")
                .dmScope(DmScope.PER_PEER)
                .bindings(List.of(ChannelBinding.forAccount("cli_app", "employee-12")))
                .build();

        assertTrue(channel.applyRoutingConfig(next), "同渠道的配置必须被接受");
        assertEquals(DmScope.PER_PEER, channel.config().dmScope());
        assertEquals("cli_app", channel.config().bindings().get(0).account());
        assertEquals("employee-12", channel.config().bindings().get(0).agentId());
        assertFalse(channel.applyRoutingConfig(ChannelConfig.of("wecom", "employee-11")),
                "别的渠道的配置必须被拒绝，由装配层换实例兜底");
    }

    @Test
    void startedChannelIsIdempotentOnRepeatedStart() {
        PlatformChannel channel = new PlatformChannel("feishu", new FakeDirectory(), ingress(), null,
                ChannelConfig.of("feishu", "employee-11"));

        channel.start();
        channel.start();

        assertTrue(true, "重复启动不得抛错（ChannelManager#startAll 每次刷新都会调用 start）");
    }

    private static AgentScopeChannelRuntime runtime(FakeDirectory directory) {
        return new AgentScopeChannelRuntime(directory, ingress(), null);
    }

    private static AgentScopeChannelRuntime runtime(FakeDirectory directory, ChannelManager registry) {
        return new AgentScopeChannelRuntime(directory, ingress(), null, registry);
    }

    private static ChannelRuntimeStatus channel(List<ChannelRuntimeStatus> channels, String channelId) {
        return channels.stream().filter(status -> status.channelId().equals(channelId)).findFirst()
                .orElseThrow(() -> new AssertionError("channel not registered: " + channelId));
    }

    private static ChannelAccountBinding binding(String bindingId, String provider, String accountKey,
                                                 long employeeId, SessionScope scope, boolean enabled) {
        return binding(bindingId, provider, accountKey, employeeId, scope, enabled, 1);
    }

    private static ChannelAccountBinding binding(String bindingId, String provider, String accountKey,
                                                 long employeeId, SessionScope scope, boolean enabled, long revision) {
        return new ChannelAccountBinding(bindingId, TENANT, provider, accountKey, "env:" + bindingId,
                employeeId, scope, enabled, revision);
    }

    /** 受理链路在这些用例里不会被触发；空装配即可让装配流程真实执行。 */
    private static ChannelIngressService ingress() {
        ChannelAccountDirectory accounts = new FakeDirectory();
        ChannelIdentityDirectory identities = (tenant, bindingId, externalUserId) -> Optional.empty();
        EmployeeCatalog employees = (tenant, employeeId) -> Optional.empty();
        ChannelTurnStore turns = (message, binding, user) -> {
            throw new UnsupportedOperationException("runtime assembly test does not accept messages");
        };
        return new ChannelIngressService(accounts, identities, employees, turns);
    }

    private static final class FakeDirectory implements ChannelAccountDirectory {

        private final List<ChannelAccountBinding> bindings = new ArrayList<>();

        void add(ChannelAccountBinding binding) {
            bindings.add(binding);
        }

        void replace(ChannelAccountBinding binding) {
            bindings.removeIf(existing -> existing.bindingId().equals(binding.bindingId()));
            bindings.add(binding);
        }

        @Override
        public Optional<ChannelAccountBinding> findById(String bindingId) {
            return bindings.stream().filter(binding -> binding.bindingId().equals(bindingId)).findFirst();
        }

        @Override
        public List<ChannelAccountBinding> findAll() {
            return List.copyOf(bindings);
        }
    }
}
