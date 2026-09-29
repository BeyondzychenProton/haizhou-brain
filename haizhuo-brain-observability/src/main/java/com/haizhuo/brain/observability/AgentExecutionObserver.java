package com.haizhuo.brain.observability;

import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.event.AgentToolSuspendedEvent;

public interface AgentExecutionObserver {
    default void onStarted(AgentExecutionRequest request) {
    }

    default void onCompleted(AgentExecutionRequest request, AgentRunCompletedEvent event) {
    }

    default void onFailed(AgentExecutionRequest request, AgentRunFailedEvent event) {
    }

    default void onSuspended(AgentExecutionRequest request, AgentToolSuspendedEvent event) {
    }

    static AgentExecutionObserver noop() {
        return new AgentExecutionObserver() { };
    }
}
