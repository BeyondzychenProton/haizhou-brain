package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.bootstrap.channel.AgentScopeChannelRuntime;
import com.haizhuo.brain.bootstrap.channel.SimulatedChannelOutboundSender;
import com.haizhuo.brain.platform.channel.ChannelAccountDirectory;
import com.haizhuo.brain.platform.channel.ChannelAdministrationService;
import com.haizhuo.brain.platform.channel.ChannelAdministrationStore;
import com.haizhuo.brain.platform.channel.ChannelDeliveryOutbox;
import com.haizhuo.brain.platform.channel.ChannelDeliveryWorker;
import com.haizhuo.brain.platform.channel.ChannelIdentityDirectory;
import com.haizhuo.brain.platform.channel.ChannelIngressService;
import com.haizhuo.brain.platform.channel.ChannelOutboundSender;
import com.haizhuo.brain.platform.channel.ChannelRuntimeAdmin;
import com.haizhuo.brain.platform.channel.ChannelTurnStore;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.tool.ToolExecutionUserDirectory;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 渠道装配（P3）：把平台侧的受理服务接在基础设施提供的目录与收件箱之上，
 * 并用 AgentScope 的 {@code ChannelManager} 承载渠道运行时注册表。
 *
 * <p>出站这边只保留现成端口与 worker，不自动调度：在没有真实 provider 发送器之前，
 * 自动认领会把每条投递记成永久失败，反而污染状态。接线点在
 * {@code ChannelDeliveryWorker} 与 {@code ChannelOutboundSender}。</p>
 */
@Configuration
public class ChannelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChannelConfiguration.class);

    @Bean
    ChannelIngressService channelIngressService(ChannelAccountDirectory accounts,
                                                 ChannelIdentityDirectory identities,
                                                 EmployeeCatalog employees,
                                                 ChannelTurnStore turns) {
        return new ChannelIngressService(accounts, identities, employees, turns);
    }

    /**
     * 渠道运行时：由持久化绑定驱动的框架渠道注册表。
     *
     * <p>框架侧的直连投递落点有意留空：平台出站统一走 outbox，绕过它才能保证
     * "发送前落库、失败可查、结果不确定不重发"这些守卫不被绕过。</p>
     */
    @Bean
    ChannelRuntimeAdmin channelRuntimeAdmin(ChannelAccountDirectory accounts, ChannelIngressService ingress) {
        return new AgentScopeChannelRuntime(accounts, ingress, null);
    }

    /** 启动即按当前绑定装配一次，让注册表与数据库一致；应用运行期由管理服务在写入后刷新。 */
    @Bean
    ApplicationRunner channelRuntimeBootstrap(ChannelRuntimeAdmin runtime) {
        return args -> {
            try {
                runtime.refreshAll();
            } catch (RuntimeException failure) {
                // 装配失败不阻止启动：入站受理按 bindingId 实时查库，不依赖注册表。
                log.error("initial channel runtime assembly failed: {}", failure.getMessage(), failure);
            }
        };
    }

    /** 渠道绑定数据的管理入口；写入后由它通知运行时重新装配。 */
    @Bean
    ChannelAdministrationService channelAdministrationService(ChannelAdministrationStore store,
                                                              ChannelAccountDirectory accounts,
                                                              EmployeeCatalog employees,
                                                              ToolExecutionUserDirectory users,
                                                              ChannelRuntimeAdmin runtime,
                                                              Clock clock) {
        return new ChannelAdministrationService(store, accounts, employees, users, runtime, clock);
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
