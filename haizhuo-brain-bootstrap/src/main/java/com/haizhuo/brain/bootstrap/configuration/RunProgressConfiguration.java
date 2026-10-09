package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.run.RunProgressQueryService;
import com.haizhuo.brain.platform.run.RunProgressQueryStore;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在应用边界装配安全的协作进度查询服务。 */
@Configuration
public class RunProgressConfiguration {
    @Bean
    RunProgressQueryService runProgressQueryService(SessionApplicationService sessions, RunProgressQueryStore store) {
        return new RunProgressQueryService(sessions, store);
    }
}
