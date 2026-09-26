package com.haizhuo.brain.bootstrap.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomRunService;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomRunStore;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomSystem;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomToolGateway;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.ApplicationRunner;

@Configuration
@Profile("meeting-mock | test")
public class MeetingMockConfiguration {
    @Bean(destroyMethod = "shutdown")
    ExecutorService meetingRunExecutor() {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    MeetingRoomToolGateway meetingRoomToolGateway(MeetingRoomSystem system, MeetingRoomRunStore store) { return new MeetingRoomToolGateway(system, store); }

    @Bean
    MeetingRoomRunService meetingRoomRunService(AgentRuntime runtime, MeetingRoomSystem system, MeetingRoomRunStore store,
                                                 MeetingRoomToolGateway gateway, ExecutorService meetingRunExecutor,
                                                 AgentScopeRuntimeProperties modelProperties,
                                                 @Value("${haizhuo.brain.meeting-room.test-user-id:1001}") long testUserId) {
        return new MeetingRoomRunService(runtime, system, store, gateway, meetingRunExecutor,
                modelProperties.provider(), modelProperties.primaryModel(), testUserId);
    }

    @Bean
    ApplicationRunner recoverMeetingRoomRuns(MeetingRoomRunService runs) { return args -> runs.recoverAfterRestart(); }
}
