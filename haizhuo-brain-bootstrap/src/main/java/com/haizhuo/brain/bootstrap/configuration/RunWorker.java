package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCancelledEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="haizhuo.brain.run-worker", name="enabled", havingValue="true")
class RunWorker {
    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);
    private final SessionRunStore store; private final AgentRuntime runtime;
    RunWorker(SessionRunStore store, AgentRuntime runtime) { this.store=store; this.runtime=runtime; }
    @Scheduled(fixedDelayString="${haizhuo.brain.run-worker.poll-delay-ms:1000}")
    void poll() {
        store.claimNextQueuedRun().ifPresent(claim -> {
            var run = claim.run();
            log.info("run.worker.claimed runId={} sessionId={} definitionVersionId={}",
                    run.id().value(), run.sessionId().value(), run.definitionVersionId());
            runtime.execute(new AgentExecutionRequest(new TenantId(1), run.userId(), run.sessionId(), run.id(),
                            TraceId.newId(), run.definitionVersionId(), claim.employeeName(), claim.instructions(),
                            claim.modelProvider(), claim.modelName(), "", List.of(), claim.input()))
                    .doOnNext(event -> {
                        if (event instanceof AgentRunCompletedEvent done) {
                            store.complete(done.runId(), done.result());
                            log.info("run.worker.completed runId={} resultLength={}", done.runId().value(),
                                    done.result() == null ? 0 : done.result().length());
                        } else if (event instanceof AgentRunFailedEvent failed) {
                            store.fail(failed.runId(), failed.message());
                            log.warn("run.worker.failed runId={} failureType=agent_runtime", failed.runId().value());
                        } else if (event instanceof AgentRunCancelledEvent cancelled) {
                            store.cancelled(cancelled.runId(), cancelled.message());
                            log.info("run.worker.cancelled runId={}", cancelled.runId().value());
                        }
                    })
                    .doOnError(error -> {
                        store.fail(run.id(), "执行失败: " + error.getClass().getSimpleName());
                        log.error("run.worker.failed runId={} failureType={}", run.id().value(),
                                error.getClass().getSimpleName());
                    })
                    .blockLast();
        });
    }
}
