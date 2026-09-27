package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="haizhuo.brain.run-worker", name="enabled", havingValue="true")
class RunWorker {
    private final SessionRunStore store; private final AgentRuntime runtime;
    RunWorker(SessionRunStore store, AgentRuntime runtime) { this.store=store; this.runtime=runtime; }
    @Scheduled(fixedDelayString="${haizhuo.brain.run-worker.poll-delay-ms:1000}")
    void poll() {
        store.claimNextQueuedRun().ifPresent(claim -> runtime.execute(new AgentExecutionRequest(new TenantId(1),claim.run().userId(),claim.run().sessionId(),claim.run().id(),TraceId.newId(),claim.run().definitionVersionId(),claim.employeeName(),claim.instructions(),claim.modelProvider(),claim.modelName(),"",List.of(),claim.input())).doOnNext(event -> { if(event instanceof AgentRunCompletedEvent done) store.complete(done.runId(),done.result()); else if(event instanceof AgentRunFailedEvent failed) store.fail(failed.runId(),failed.message()); }).doOnError(error -> store.fail(claim.run().id(),"执行失败: "+error.getClass().getSimpleName())).blockLast());
    }
}
