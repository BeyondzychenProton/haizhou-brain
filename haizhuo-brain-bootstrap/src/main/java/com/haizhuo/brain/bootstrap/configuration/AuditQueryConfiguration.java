package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.audit.AuditQueryService;
import com.haizhuo.brain.platform.audit.AuditQueryStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将 Spring 装配留在应用边界，审计查询服务保持框架无关。 */
@Configuration
public class AuditQueryConfiguration {
    @Bean
    AuditQueryService auditQueryService(AuditQueryStore store, Clock clock) {
        return new AuditQueryService(store, clock);
    }
}
