package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionRunConfiguration {
    @Bean
    SessionApplicationService sessionApplicationService(SessionRunStore store, EmployeeCatalog employees, Clock clock) {
        return new SessionApplicationService(store, employees, clock);
    }
}
