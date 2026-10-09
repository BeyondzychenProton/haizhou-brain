package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import java.util.Objects;
import reactor.core.publisher.Flux;

/** 将不可变模型连接引用绑定后委托给 AgentScope；此组件自身不会启动执行。 */
public final class ModelConnectionBindingAgentRuntime implements AgentRuntime {
    private final AgentRuntime delegate;
    private final RunModelConnectionSnapshotBinder binder;

    public ModelConnectionBindingAgentRuntime(AgentRuntime delegate, RunModelConnectionSnapshotBinder binder) {
        this.delegate = Objects.requireNonNull(delegate);
        this.binder = Objects.requireNonNull(binder);
    }

    @Override
    public Flux<BrainAgentEvent> execute(AgentExecutionRequest request) {
        return Flux.defer(() -> {
            final AgentExecutionRequest bound;
            try {
                bound = binder.bind(request);
            } catch (RuntimeException unavailable) {
                return Flux.just(new AgentRunFailedEvent(request.runId(),
                        "MODEL_CONNECTION_UNAVAILABLE: 模型连接的冻结版本缺失或不匹配，执行未开始。"));
            }
            return delegate.execute(bound);
        });
    }
}
