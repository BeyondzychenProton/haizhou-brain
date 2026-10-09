package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.run.AgentResultRepository;
import com.haizhuo.brain.platform.run.RunFeedbackRepository;
import com.haizhuo.brain.platform.run.RunFeedbackService;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.platform.session.SessionHistoryQuery;
import com.haizhuo.brain.platform.session.SessionHistoryQueryService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在应用边界装配平台服务，不向 platform 模块引入 Spring 依赖。 */
@Configuration
public class SessionHistoryConfiguration {
    @Bean
    SessionHistoryQueryService sessionHistoryQueryService(SessionHistoryQuery query) {
        return new SessionHistoryQueryService(query);
    }

    @Bean
    RunFeedbackService runFeedbackService(SessionApplicationService sessions, AgentResultRepository results,
                                          RunFeedbackRepository feedback, Clock clock) {
        return new RunFeedbackService(sessions, results, feedback, clock);
    }
}
