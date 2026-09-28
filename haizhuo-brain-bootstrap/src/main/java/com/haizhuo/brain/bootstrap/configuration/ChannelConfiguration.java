package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.channel.ChannelAccountDirectory;
import com.haizhuo.brain.platform.channel.ChannelIdentityDirectory;
import com.haizhuo.brain.platform.channel.ChannelIngressService;
import com.haizhuo.brain.platform.channel.ChannelTurnStore;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
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
}
