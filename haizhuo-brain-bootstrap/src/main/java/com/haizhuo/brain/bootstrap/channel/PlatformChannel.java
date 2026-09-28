package com.haizhuo.brain.bootstrap.channel;

import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAccountDirectory;
import com.haizhuo.brain.platform.channel.ChannelAcceptance;
import com.haizhuo.brain.platform.channel.ChannelIngressService;
import com.haizhuo.brain.platform.channel.VerifiedChannelMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 平台渠道适配器（P3）：复用 AgentScope 的 {@link Channel} 边界，
 * 把入站消息翻译成平台受理，把出站交付委托给已装配的投递消费者。
 *
 * <p>这里刻意"只做边界"：受理之后的执行仍由平台既有的 Run 调度器完成，
 * 不让 Gateway 直接驱动 Agent——否则平台会出现两套 Run 状态机，
 * 而排队、取消、审批与事件持久化都挂在平台那一套上。</p>
 *
 * <p>外部用户身份不参与路由：绑定关系只能由服务端持有的
 * {@link ChannelAccountDirectory} 提供，消息体里带来的标识一律不信任。</p>
 */
public class PlatformChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(PlatformChannel.class);

    private final String channelId;
    private final ChannelAccountDirectory accounts;
    private final ChannelIngressService ingress;
    private final ChannelOutboundConsumer outbound;
    private final ChannelConfig config;

    /**
     * @param outbound 框架侧的直接投递落点；平台侧的出站投递走 outbox worker，
     *                 因此这里允许为 null，此时只记录警告而不是静默丢弃。
     */
    public PlatformChannel(String channelId, ChannelAccountDirectory accounts, ChannelIngressService ingress,
                           ChannelOutboundConsumer outbound, ChannelConfig config) {
        this.channelId = Objects.requireNonNull(channelId);
        this.accounts = Objects.requireNonNull(accounts);
        this.ingress = Objects.requireNonNull(ingress);
        this.outbound = outbound;
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public String channelId() {
        return channelId;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage inbound) {
        // 受理是阻塞式数据库事务，放到 boundedElastic 上执行，避免占用事件循环线程。
        return Mono.fromCallable(() -> ingress.accept(verified(inbound)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(accepted -> acknowledgement(inbound, accepted));
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (outbound == null) {
            // 未装配直接投递通道是当前的有意状态：出站统一走 outbox，避免绕过投递守卫。
            log.warn("Channel {} has no direct outbound consumer; delivery address={} dropped after outbox policy",
                    channelId, address == null ? null : address.to());
            return;
        }
        outbound.accept(address, messages);
    }

    /**
     * 入站消息必须携带稳定的外部事件标识：去重与幂等重放全靠它，
     * 缺失时宁可拒绝受理，也不能让重复消息各自建一个 Run。
     */
    private VerifiedChannelMessage verified(InboundMessage inbound) {
        ChannelAccountBinding binding = accounts.findById(inbound.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Channel account is not bound"));
        String eventId = inbound.messages().stream()
                .map(Msg::getId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Inbound message requires a stable id"));
        String peer = inbound.peer() == null ? inbound.senderId() : inbound.peer().key();
        return new VerifiedChannelMessage(binding.bindingId(), binding.provider(), eventId, peer,
                inbound.senderId(), text(inbound), peer);
    }

    private static String text(InboundMessage inbound) {
        String text = inbound.messages().stream()
                .map(Msg::getTextContent)
                .filter(Objects::nonNull)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
        return text.isBlank() ? "(empty message)" : text;
    }

    private static Msg acknowledgement(InboundMessage inbound, ChannelAcceptance accepted) {
        String note = accepted.duplicate()
                ? "该消息已受理过，未重复创建运行。"
                : "已受理，运行完成后会在这里答复。";
        return Msg.builder().name("platform").textContent(note).build();
    }

    /** 框架侧的直接投递落点；渠道实现需要自己完成真实发送。 */
    @FunctionalInterface
    public interface ChannelOutboundConsumer {
        void accept(OutboundAddress address, List<Msg> messages);
    }
}
