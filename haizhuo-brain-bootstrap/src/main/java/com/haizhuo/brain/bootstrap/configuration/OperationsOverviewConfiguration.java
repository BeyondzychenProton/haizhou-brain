package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.api.admin.OperationsOverviewProvider;
import com.haizhuo.brain.observability.LangfuseProperties;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 将运维事实接入当前运行实例实际装配的 Bean 和功能开关。 */
@Configuration(proxyBeanMethods = false)
class OperationsOverviewConfiguration {
    @Bean
    OperationsOverviewProvider operationsOverviewProvider(Environment environment,
                                                           ObjectProvider<AgentRuntime> runtime,
                                                           ObjectProvider<RunWorker> runWorker,
                                                           ObjectProvider<ChannelDeliveryDispatcher> channelWorker,
                                                           ObjectProvider<OpenTelemetry> openTelemetry,
                                                           LangfuseProperties langfuse,
                                                           Clock clock) {
        return new RuntimeOperationsOverviewProvider(environment, runtime.getIfAvailable() != null,
                runWorker.getIfAvailable() != null, channelWorker.getIfAvailable() != null,
                openTelemetry.getIfAvailable() != null, langfuse, clock);
    }
}
