package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.observability.LangfuseProperties;
import com.haizhuo.brain.observability.LangfuseTraceAttributeSpanProcessor;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.actuate.autoconfigure.tracing.SdkTracerProviderBuilderCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpHttpSpanExporterBuilderCustomizer;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 Spring Boot 的 OTLP exporter 对接到 Langfuse。AgentScope 的原生中间件读取
 * {@link GlobalOpenTelemetry}，因此启动时需要把 Boot 管理的 SDK 注册为全局实例。
 */
@Configuration(proxyBeanMethods = false)
public class LangfuseOtlpConfiguration {
    private static final Logger log = LoggerFactory.getLogger(LangfuseOtlpConfiguration.class);

    @Bean
    OtlpHttpSpanExporterBuilderCustomizer langfuseOtlpCustomizer(LangfuseProperties properties) {
        return builder -> {
            if (!properties.isEnabled()) return;
            builder.setEndpoint(properties.otlpEndpoint());
            String authorization = properties.basicAuthorization();
            if (authorization != null) builder.addHeader("Authorization", authorization);
            else log.warn("Langfuse OTLP is enabled without public/secret keys; requests will be sent without Authorization");
            builder.addHeader("x-langfuse-ingestion-version", properties.ingestionVersion());
        };
    }

    @Bean
    SdkTracerProviderBuilderCustomizer langfuseTraceAttributes(
            LangfuseProperties properties,
            LangfuseTraceAttributeSpanProcessor processor) {
        return builder -> {
            if (properties.isEnabled()) {
                builder.addSpanProcessor(processor);
            }
        };
    }

    @Bean
    SmartLifecycle registerGlobalOpenTelemetry(ObjectProvider<OpenTelemetry> openTelemetry) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                openTelemetry.ifAvailable(otel -> {
                    try {
                        GlobalOpenTelemetry.set(otel);
                    } catch (IllegalStateException alreadyRegistered) {
                        log.warn("Global OpenTelemetry was already registered; Langfuse exporter may not receive AgentScope spans");
                    }
                });
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return Integer.MIN_VALUE;
            }
        };
    }
}
