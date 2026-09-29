package com.haizhuo.brain.observability;

import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.event.AgentToolSuspendedEvent;
import org.springframework.stereotype.Component;

@Component
public class DefaultAgentExecutionObserver implements AgentExecutionObserver {
    private final LangfuseObservationRecorder recorder;

    public DefaultAgentExecutionObserver(LangfuseObservationRecorder recorder) {
        this.recorder = recorder;
    }

    public void onStarted(AgentExecutionRequest request) {
        safe(() -> recorder.runStarted(request));
    }

    public void onCompleted(AgentExecutionRequest request, AgentRunCompletedEvent event) {
        safe(() -> recorder.runCompleted(request, event));
    }

    public void onFailed(AgentExecutionRequest request, AgentRunFailedEvent event) {
        safe(() -> recorder.runFailed(request, event));
    }

    public void onSuspended(AgentExecutionRequest request, AgentToolSuspendedEvent event) {
        safe(() -> recorder.runSuspended(request, event.toolCalls().size()));
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 观测系统不能改变 Agent 主链路。
        }
    }
}
