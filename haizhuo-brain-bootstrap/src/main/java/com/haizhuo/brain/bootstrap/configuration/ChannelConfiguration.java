package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.bootstrap.channel.SimulatedChannelOutboundSender;
import com.haizhuo.brain.platform.channel.ChannelAccountDirectory;
import com.haizhuo.brain.platform.channel.ChannelDeliveryOutbox;
import com.haizhuo.brain.platform.channel.ChannelDeliveryWorker;
import com.haizhuo.brain.platform.channel.ChannelIdentityDirectory;
import com.haizhuo.brain.platform.channel.ChannelIngressService;
import com.haizhuo.brain.platform.channel.ChannelOutboundSender;
import com.haizhuo.brain.platform.channel.ChannelTurnStore;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 渠道入站装配（P3）：把平台侧的受理服务接在基础设施提供的目录与收件箱之上。
 *
 * <p>出站这边只保留现成端口与 worker，不自动调度：在没有真实 provider 发送器之前，
 * 自动认领会把每条投递记成永久失败，反而污染状态。接线点在
 * {@code ChannelDeliveryWorker} 与 {@code ChannelOutboundSender}。</p>
 */
@Configuration
public class ChannelConfiguration {

    @Bean
    ChannelIngressService channelIngressService(ChannelAccountDirectory accounts,
                                                 ChannelIdentityDirectory identities,
                                                 EmployeeCatalog employees,
                                                 ChannelTurnStore turns) {
        return new ChannelIngressService(accounts, identities, employees, turns);
    }

    /**
     * 出站投递 worker：只装配，不驱动。驱动由 {@code ChannelDeliveryDispatcher} 按开关启动，
     * 这样没有真实 sender 时不会把每条待投递记成永久失败。
     */
    @Bean
    ChannelDeliveryWorker channelDeliveryWorker(ChannelDeliveryOutbox outbox,
                                                List<ChannelOutboundSender> senders) {
        Map<String, ChannelOutboundSender> byProvider = senders.stream().collect(Collectors.toMap(
                ChannelOutboundSender::provider, Function.identity(), (left, right) -> left));
        return new ChannelDeliveryWorker(outbox, byProvider);
    }

    /**
     * 模拟渠道发送器：用真实 HTTP 契约验证出站链路，未配置端点时本地记录并视为已投递。
     * 换真实 IM 时新增一个 {@link ChannelOutboundSender} 实现即可，平台侧无需改动。
     */
    @Bean
    ChannelOutboundSender simulatedChannelOutboundSender(
            @Value("${haizhuo.brain.channel.simulated.endpoint:}") String endpoint) {
        return new SimulatedChannelOutboundSender(endpoint);
    }
}
