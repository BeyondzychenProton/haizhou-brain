package com.haizhuo.brain.observability;

import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import org.springframework.stereotype.Component;

@Component
public class DefaultAgentExecutionObserver implements AgentExecutionObserver {
    public void onStarted(AgentExecutionRequest request) {
    }

    public void onCompleted(AgentExecutionRequest request) {
    }

    public void onFailed(AgentExecutionRequest request, Throwable error) {
    }
}
