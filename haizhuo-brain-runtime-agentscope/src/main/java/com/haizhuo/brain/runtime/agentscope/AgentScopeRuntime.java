package com.haizhuo.brain.runtime.agentscope;
import com.haizhuo.brain.kernel.identity.AgentTaskId;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.event.AgentTaskCompletedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
@Component
public class AgentScopeRuntime implements AgentRuntime {
    public Flux<BrainAgentEvent> execute(AgentExecutionRequest request) {
        return Flux.just(new AgentTaskCompletedEvent(request.taskId(), "AgentScope runtime adapter is ready"));
    }
}
