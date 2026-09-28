package com.haizhuo.brain.runtime.agentscope.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.LocalSessionTurnGate;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.SessionIdUtils;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * P0 框架契约探针：核对 AgentScope Java 2.0.3 harness 是否真的提供多渠道所需的
 * Channel/Gateway/游标/忙时/子 Agent 边界。全部断言只依赖确定性行为，不调用模型、不访问网络。
 *
 * <p>本探针证明"框架具备该能力"，不证明"平台已完成接入"。
 */
class AgentScopeGatewayContractTest {

    private static final String MAIN_AGENT = "haizhuo-main";

    @Test
    void frameworkSessionIdIsDerivedFromStableHashOfChannelKeys() {
        // 框架用 SessionIdUtils.deterministicHash 生成渠道会话键，再拼 "gw-" 前缀（见 HarnessGateway#generateSessionId）。
        String first = SessionIdUtils.deterministicHash("chatui", "user-1");
        String repeated = SessionIdUtils.deterministicHash("chatui", "user-1");
        String otherPeer = SessionIdUtils.deterministicHash("chatui", "user-2");
        String otherChannel = SessionIdUtils.deterministicHash("wecom", "user-1");

        assertEquals(first, repeated, "同一渠道与对端必须得到稳定会话键，平台才能复算并做映射");
        assertNotEquals(first, otherPeer, "不同对端不得共享渠道会话");
        assertNotEquals(first, otherChannel, "不同渠道的同名对端不得共享渠道会话");
        assertEquals(12, first.length(), "会话键是 SHA-256 前 6 字节的十六进制");
        assertTrue(first.matches("[0-9a-f]{12}"), "会话键必须是十六进制小写，便于作为外部键持久化");
    }

    @Test
    void inboundMessageCarriesPlatformIdentityIntoRuntimeContext() {
        Msg message = Msg.builder().role(MsgRole.USER).textContent("查一下会议室").build();
        InboundMessage inbound = InboundMessage.dm(CHANNEL_ID, "user-1", List.of(message));

        assertTrue(inbound.isDm(), "私聊入站必须能被识别，平台据此决定会话隔离粒度");
        assertEquals(CHANNEL_ID, inbound.channelId());
        assertEquals("user-1", inbound.senderId(), "渠道侧发送者标识必须随入站消息带出，供平台做身份绑定");

        RuntimeContext trusted = RuntimeContext.builder()
                .userId("1001")
                .sessionId("gw-" + SessionIdUtils.deterministicHash(CHANNEL_ID, "user-1"))
                .build();
        InboundMessage bound = inbound.withRuntimeContext(trusted);

        assertEquals("1001", bound.runtimeContext().getUserId(),
                "可信身份由平台注入，渠道自报的 senderId 不能直接当平台用户");
        assertEquals(trusted.getSessionId(), bound.runtimeContext().getSessionId());
    }

    @Test
    void defaultChannelScopeSharesOneSessionAcrossPeers() {
        // 默认 DmScope 是 MAIN：整个渠道共用一个会话键。这是接入红线，不是缺陷。
        assertEquals(DmScope.MAIN, DmScope.defaultScope(), "默认隔离粒度必须显式核对，不能假设按用户隔离");

        ChannelRouter router = new ChannelRouter(MAIN_AGENT);
        ChannelConfig config = ChannelConfig.of(CHANNEL_ID, MAIN_AGENT);

        MsgContext alpha = router.resolveRoute(config, dm("user-1")).context();
        MsgContext beta = router.resolveRoute(config, dm("user-2")).context();

        assertEquals(alpha.canonicalKey(), beta.canonicalKey(),
                "默认 MAIN 粒度下同渠道不同对端共用一个会话，直接上线会导致串号");
    }

    @Test
    void perPeerScopeSeparatesPeersAndKeepsSamePeerStable() {
        ChannelRouter router = new ChannelRouter(MAIN_AGENT);
        ChannelConfig config = ChannelConfig.builder(CHANNEL_ID)
                .defaultAgentId(MAIN_AGENT)
                .dmScope(DmScope.PER_PEER)
                .build();

        RouteResult alpha = router.resolveRoute(config, dm("user-1"));
        RouteResult again = router.resolveRoute(config, dm("user-1"));
        RouteResult beta = router.resolveRoute(config, dm("user-2"));

        assertEquals(MAIN_AGENT, alpha.agentId(), "未命中绑定时回落到渠道默认 Agent");
        assertEquals(alpha.context().canonicalKey(), again.context().canonicalKey(),
                "同一对端必须路由到同一会话键，否则历史会被拆散");
        assertNotEquals(alpha.context().canonicalKey(), beta.context().canonicalKey(),
                "显式 PER_PEER 后不同对端必须落在不同会话键，否则读取到他人会话");
        assertNotNull(alpha.outboundAddress(), "路由结果必须带出站地址，否则回复无处投递");
    }

    @Test
    void localTurnGateQueuesConcurrentTurnInsteadOfRejecting() throws Exception {
        // LocalSessionTurnGate 是阻塞排队语义；TurnBusyException 留给共享/非本地实现去抛。
        assertTrue(java.util.Arrays.stream(TurnBusyException.class.getMethods())
                        .anyMatch(candidate -> candidate.getName().equals("getGateKey")),
                "busy 信号必须带出门键，平台才能定位是哪个会话在运行");

        LocalSessionTurnGate gate = new LocalSessionTurnGate();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TurnLease lease = gate.acquire("gw-session-1");
            assertTrue(gate.isRunning("gw-session-1"), "持有租约期间必须报告会话忙碌");

            Future<Boolean> queued = pool.submit(() -> {
                gate.acquire("gw-session-1").close();
                return true;
            });
            Thread.sleep(200);
            assertFalse(queued.isDone(), "本地门在并发轮次上排队等待，而不是快速拒绝");

            lease.close();
            assertTrue(queued.get(5, TimeUnit.SECONDS), "释放租约后排队中的轮次必须获得执行机会");
            assertFalse(gate.isRunning("gw-session-1"), "释放租约后会话必须重新可运行");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void outboundAddressRoundTripsForDeliveryOutbox() {
        OutboundAddress direct = OutboundAddress.direct(CHANNEL_ID, "user-1");
        OutboundAddress restored = OutboundAddress.fromMap(direct.toMap());
        assertEquals(direct, restored, "出站地址必须可序列化往返，出站投递才能落 outbox 并跨重启恢复");

        OutboundAddress withAccount = OutboundAddress.withAccount(CHANNEL_ID, "acct-9", "user-1");
        assertEquals(withAccount, OutboundAddress.fromMap(withAccount.toMap()));
    }

    @Test
    void channelManagerRegistersChannelAndRoutesOutboundDelivery() {
        ChannelManager manager = new ChannelManager();
        RecordingChannel channel = new RecordingChannel();
        manager.register(channel);

        assertEquals(List.of(CHANNEL_ID), manager.channelIds(), "注册后必须可按渠道标识检索");
        assertTrue(manager.getChannel(CHANNEL_ID).isPresent());
        assertFalse(manager.unregister("missing-channel"), "注销未知渠道必须返回 false 而不是抛错");

        OutboundAddress address = OutboundAddress.direct(CHANNEL_ID, "user-1");
        manager.deliver(address, List.of(message("好的")));

        assertEquals(List.of(address), channel.delivered, "出站投递必须落到对应渠道实现");
    }

    @Test
    void subagentProtocolCarriesApprovalAndProgressSemantics() {
        // 子 Agent 回传协议需要覆盖进度、工具与审批，平台才能在不泄露内部消息的前提下展示状态。
        for (String required : List.of("RUN_STARTED", "RUN_FINISHED", "RUN_ERROR", "TEXT_DELTA",
                "TOOL_CALL_START", "TOOL_CALL_END", "TOOL_RESULT", "REQUIRE_CONFIRM", "STATUS")) {
            assertEquals(required, RemoteEventType.valueOf(required).name(),
                    "缺少协议事件类型会让父子 Run 的进度或审批无法对齐：" + required);
        }
    }

    @Test
    void gatewayAbstractionsArePresentOnTheClasspath() {
        // 逐项确认官方示例依赖的抽象在 Java 2.0.3 确实存在，避免按 Python 版假设设计。
        List<String> required = List.of(
                "io.agentscope.harness.agent.gateway.Gateway",
                "io.agentscope.harness.agent.gateway.HarnessGateway",
                "io.agentscope.harness.agent.gateway.GatewayBootstrap",
                "io.agentscope.harness.agent.gateway.channel.Channel",
                "io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel",
                "io.agentscope.harness.agent.gateway.SessionTurnGate",
                "io.agentscope.harness.agent.workspace.plan.PlanModeManager",
                "io.agentscope.harness.agent.tool.PlanModeTools",
                "io.agentscope.harness.agent.middleware.PlanModeMiddleware",
                "io.agentscope.harness.agent.team.LocalTeamClient",
                "io.agentscope.harness.agent.middleware.TeamsMiddleware",
                "io.agentscope.harness.agent.subagent.SubagentFactory",
                "io.agentscope.harness.agent.bus.MessageBus",
                "io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget");
        for (String type : required) {
            assertInstanceOf(Class.class, loaded(type), "框架缺少该抽象会让对应阶段必须自研：" + type);
        }
    }

    @Test
    void harnessGatewayExposesDeliveryAndWakeupEntryPoints() throws Exception {
        Class<?> gateway = Class.forName("io.agentscope.harness.agent.gateway.HarnessGateway");
        for (String method : List.of("bindMainAgent", "registerAgent", "deliverToSession",
                "registerExternalSession", "isSessionRunning", "runWakeup", "runSubagent", "exposeSubagent")) {
            assertTrue(java.util.Arrays.stream(gateway.getMethods())
                            .anyMatch(candidate -> candidate.getName().equals(method)),
                    "网关缺少入口会让平台被迫自研该链路：" + method);
        }
        assertTrue(java.util.Arrays.stream(HarnessGateway.class.getMethods())
                .anyMatch(candidate -> candidate.getName().equals("setSessionTurnGate")),
                "忙时策略必须可替换，多实例部署才能换成共享实现");
    }

    private static Class<?> loaded(String type) {
        try {
            return Class.forName(type);
        } catch (ClassNotFoundException error) {
            return null;
        }
    }

    private static InboundMessage dm(String senderId) {
        return InboundMessage.dm(CHANNEL_ID, senderId, List.of(message("你好")));
    }

    private static Msg message(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static final String CHANNEL_ID = "chatui";

    /** 最小渠道实现：只记录出站，用于验证 ChannelManager 的投递链路。 */
    private static final class RecordingChannel implements Channel {
        private final List<OutboundAddress> delivered = new CopyOnWriteArrayList<>();
        private final ChannelConfig config = ChannelConfig.of(CHANNEL_ID, MAIN_AGENT);

        @Override
        public String channelId() {
            return CHANNEL_ID;
        }

        @Override
        public ChannelConfig config() {
            return config;
        }

        @Override
        public Mono<Msg> dispatch(InboundMessage inbound) {
            return Mono.empty();
        }

        @Override
        public void deliver(OutboundAddress address, List<Msg> messages) {
            delivered.add(address);
        }
    }
}
