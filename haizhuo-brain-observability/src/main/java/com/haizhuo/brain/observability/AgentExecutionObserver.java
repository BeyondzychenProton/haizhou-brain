package com.haizhuo.brain.observability;

import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;

public interface AgentExecutionObserver {
    void onStarted(AgentExecutionRequest request);

    void onCompleted(AgentExecutionRequest request);

    void onFailed(AgentExecutionRequest request, Throwable error);
}
