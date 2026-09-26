package com.haizhuo.brain.bootstrap.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomRunService;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomSystem;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomToolGateway;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("meeting-mock | test")
public class MeetingMockConfiguration {
    @Bean(destroyMethod = "shutdown")
    ExecutorService meetingRunExecutor() { return Executors.newFixedThreadPool(2); }

    @Bean
    MeetingRoomToolGateway meetingRoomToolGateway(MeetingRoomSystem system) { return new MeetingRoomToolGateway(system); }

    @Bean
    MeetingRoomRunService meetingRoomRunService(AgentRuntime runtime, MeetingRoomSystem system, MeetingRoomToolGateway gateway, ExecutorService meetingRunExecutor,
                                                 @Value("${haizhuo.brain.agent.primary-model:qwen-plus}") String model) {
        return new MeetingRoomRunService(runtime, system, gateway, meetingRunExecutor, model);
    }
}
