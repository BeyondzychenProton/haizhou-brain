package com.haizhuo.brain.bootstrap.channel;

import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAccountDirectory;
import com.haizhuo.brain.platform.channel.ChannelIngressService;
import com.haizhuo.brain.platform.channel.ChannelRuntimeAdmin;
import com.haizhuo.brain.platform.channel.SessionScope;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.HashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渠道运行时装配（P3 方向 4）：把 AgentScope 的渠道注册表接到平台的持久化绑定上。
 *
 * <p>复用框架的三件事，不另造一套渠道管理：</p>
 * <ul>
 *   <li>{@link ChannelManager} 作为渠道注册表与生命周期（注册 / 注销 / 启动 / 投递）；</li>
 *   <li>{@link ChannelConfig} 与 {@link ChannelBinding} 作为渠道配置与路由的规范载体；</li>
 *   <li>{@link Channel#applyRoutingConfig} 作为配置热更新钩子，避免换配置就重建实例。</li>
 * </ul>
 *
 * <p>刻意的边界：<b>不让 Gateway 直接驱动 Agent</b>。执行仍由平台 Run 调度器负责，
 * 否则平台会出现两套 Run 状态机，而排队、取消、审批与事件持久化都挂在平台那一套上。</p>
 */
public class AgentScopeChannelRuntime implements ChannelRuntimeAdmin {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeChannelRuntime.class);

    /** 平台用员工标识承载 Agent：一个渠道账号绑定指向一个员工。 */
    private static final String AGENT_ID_PREFIX = "employee-";

    private final ChannelManager channels;
    private final ChannelAccountDirectory directory;
    private final ChannelIngressService ingress;
    private final PlatformChannel.ChannelOutboundConsumer outbound;
    private final String instanceLabel = "channel-runtime-" + UUID.randomUUID().toString().substring(0, 8);
    private final Map<String, LoadedAccount> loadedAccounts = new HashMap<>();

    /**
     * @param outbound 框架侧的直接投递落点；平台出站统一走 outbox，
     *                 因此允许为 null，由 {@link PlatformChannel} 记录告警而不是静默丢弃。
     */
    public AgentScopeChannelRuntime(ChannelAccountDirectory directory, ChannelIngressService ingress,
                                    PlatformChannel.ChannelOutboundConsumer outbound) {
        this(directory, ingress, outbound, new ChannelManager());
    }

    AgentScopeChannelRuntime(ChannelAccountDirectory directory, ChannelIngressService ingress,
                             PlatformChannel.ChannelOutboundConsumer outbound, ChannelManager channels) {
        this.directory = Objects.requireNonNull(directory);
        this.ingress = Objects.requireNonNull(ingress);
        this.outbound = outbound;
        this.channels = Objects.requireNonNull(channels);
    }

    @Override
    public synchronized void refreshAll() {
        List<ChannelAccountBinding> configured = directory.findAll();
        Map<String, List<ChannelAccountBinding>> byProvider = enabledBindingsByProvider(configured);
        Set<String> enabledIds = byProvider.values().stream().flatMap(List::stream)
                .map(ChannelAccountBinding::bindingId).collect(Collectors.toSet());
        loadedAccounts.keySet().removeIf(bindingId -> !enabledIds.contains(bindingId));

        // 1) 卸载：没有任何启用绑定的渠道不该继续留在注册表里（unregister 会顺带 stop）。
        for (String channelId : channels.channelIds()) {
            if (byProvider.containsKey(channelId)) {
                continue;
            }
            channels.unregister(channelId);
            log.info("channel runtime unloaded {}: no enabled binding remains", channelId);
        }

        // 2) 装载或热更新。channelId 即 provider：一个渠道进程只承载一个 provider。
        for (Map.Entry<String, List<ChannelAccountBinding>> entry : byProvider.entrySet()) {
            String channelId = entry.getKey();
            ChannelConfig config = frameworkConfig(channelId, entry.getValue());
            Channel existing = channels.getChannel(channelId).orElse(null);
            if (existing == null) {
                channels.register(new PlatformChannel(channelId, directory, ingress, outbound, config));
                markLoaded(entry.getValue());
                log.info("channel runtime loaded {} with {} enabled binding(s)", channelId, entry.getValue().size());
                continue;
            }
            if (existing.applyRoutingConfig(config)) {
                markLoaded(entry.getValue());
                continue;
            }
            // 渠道拒绝了新配置：换实例，避免注册表里留着过期的路由表。
            removeProvider(entry.getValue().get(0).provider());
            channels.unregister(channelId);
            channels.register(new PlatformChannel(channelId, directory, ingress, outbound, config));
            markLoaded(entry.getValue());
            log.info("channel runtime reloaded {}: previous instance rejected the routing config", channelId);
        }

        // 3) 启动。startAll 遍历全部渠道且自带异常兜底，渠道自身的 start 幂等，
        //    因此每次刷新都调用它是安全的，新装载的渠道也一定会被启动。
        channels.startAll();
    }

    @Override
    public synchronized List<ChannelRuntimeStatus> channels() {
        boolean running = channels.isStarted();
        List<ChannelRuntimeStatus> statuses = new ArrayList<>();
        List<ChannelAccountBinding> configured = directory.findAll();
        for (Channel channel : channels.getAllChannels()) {
            ChannelConfig config = channel.config();
            List<AccountLoadSnapshot> snapshots = configured.stream()
                    .filter(binding -> binding.provider().equals(channel.channelId()))
                    .map(this::snapshot).toList();
            statuses.add(new ChannelRuntimeStatus(channel.channelId(),
                    config == null ? null : config.defaultAgentId(),
                    config == null || config.dmScope() == null
                            ? SessionScope.defaultScope().name() : config.dmScope().name(),
                    config == null || config.bindings() == null ? 0 : config.bindings().size(),
                    running, instanceLabel, Instant.now(), snapshots));
        }
        Set<String> observedProviders = statuses.stream().map(ChannelRuntimeStatus::channelId).collect(Collectors.toSet());
        configured.stream().map(ChannelAccountBinding::provider).distinct()
                .filter(provider -> !observedProviders.contains(provider))
                .forEach(provider -> {
                    List<ChannelAccountBinding> providerAccounts = configured.stream()
                            .filter(binding -> binding.provider().equals(provider)).toList();
                    List<AccountLoadSnapshot> snapshots = providerAccounts.stream().map(this::snapshot).toList();
                    statuses.add(new ChannelRuntimeStatus(provider, null,
                            strictestSessionScope(providerAccounts).name(), 0, false, instanceLabel,
                            Instant.now(), snapshots));
                });
        return statuses;
    }

    @Override
    public String instanceLabel() {
        return instanceLabel;
    }

    @Override
    public synchronized List<AccountLoadSnapshot> accountSnapshots() {
        return directory.findAll().stream().map(this::snapshot).toList();
    }

    private AccountLoadSnapshot snapshot(ChannelAccountBinding binding) {
        LoadedAccount loaded = loadedAccounts.get(binding.bindingId());
        if (!binding.enabled()) {
            return new AccountLoadSnapshot(binding.bindingId(), binding.revision(), null, "UNLOADED");
        }
        if (loaded == null) {
            return new AccountLoadSnapshot(binding.bindingId(), binding.revision(), null, "UNKNOWN");
        }
        String state = loaded.revision() == binding.revision() ? "LOADED" : "STALE";
        return new AccountLoadSnapshot(binding.bindingId(), binding.revision(), loaded.revision(), state);
    }

    private void markLoaded(List<ChannelAccountBinding> bindings) {
        for (ChannelAccountBinding binding : bindings) {
            loadedAccounts.put(binding.bindingId(), new LoadedAccount(binding.provider(), binding.revision()));
        }
    }

    private void removeProvider(String provider) {
        loadedAccounts.entrySet().removeIf(entry -> provider.equals(entry.getValue().provider()));
    }

    /**
     * 把持久化绑定投影成框架的渠道配置：每条启用绑定是一条账号级路由
     * （外部账号 → 承载它的员工），会话粒度取该渠道内最严的一条。
     */
    private static ChannelConfig frameworkConfig(String channelId, List<ChannelAccountBinding> bindings) {
        List<ChannelBinding> routes = new ArrayList<>(bindings.size());
        for (ChannelAccountBinding binding : bindings) {
            routes.add(ChannelBinding.forAccount(binding.externalAccountKey(), agentId(binding)));
        }
        return ChannelConfig.builder(channelId)
                // 渠道默认 Agent 取首条绑定，作为未命中账号级路由时的回落目标。
                .defaultAgentId(agentId(bindings.get(0)))
                .dmScope(DmScope.valueOf(strictestSessionScope(bindings).name()))
                .bindings(routes)
                .build();
    }

    private static String agentId(ChannelAccountBinding binding) {
        return AGENT_ID_PREFIX + binding.defaultEmployeeId();
    }

    /**
     * 同一渠道内绑定的会话粒度不一致时取最严的一条：放宽会让不同对端共用一个会话，
     * 是不可接受的失败方向；收紧只影响会话数量。
     */
    private static SessionScope strictestSessionScope(List<ChannelAccountBinding> bindings) {
        SessionScope strictest = SessionScope.defaultScope();
        for (ChannelAccountBinding binding : bindings) {
            SessionScope scope = binding.sessionScope() == null
                    ? SessionScope.defaultScope() : binding.sessionScope();
            if (scope.ordinal() > strictest.ordinal()) {
                strictest = scope;
            }
        }
        return strictest;
    }

    private static Map<String, List<ChannelAccountBinding>> enabledBindingsByProvider(
            List<ChannelAccountBinding> configured) {
        Map<String, List<ChannelAccountBinding>> byProvider = new LinkedHashMap<>();
        for (ChannelAccountBinding binding : configured) {
            if (!binding.enabled()) {
                continue;
            }
            byProvider.computeIfAbsent(binding.provider(), ignored -> new ArrayList<>()).add(binding);
        }
        return byProvider;
    }

    private record LoadedAccount(String provider, long revision) {
    }
}
