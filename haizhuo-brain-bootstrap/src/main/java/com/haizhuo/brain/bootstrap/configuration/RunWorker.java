package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.run.RunExecutionService;
import com.haizhuo.brain.platform.run.RunExecutionStore;
import com.haizhuo.brain.platform.tool.ToolExecutionWorker;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 架在持久化服务之上的轻量调度器（规格 §3.4 的迁移形态）：每一拍先按节奏结算过期租约，
 * 再优先排空可执行的工具工作（它们完成后会让 Run 重新排队），最后驱动排队的 Run。
 * 所有认领/租约/fence 语义都住在 platform 服务里，本类不含任何业务逻辑。
 */
@Component
@ConditionalOnProperty(prefix = "haizhuo.brain.run-worker", name = "enabled", havingValue = "true")
class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    private final RunExecutionService runExecutionService;
    private final ToolExecutionWorker toolExecutionWorker;
    private final RunExecutionStore executionStore;
    private final RunWorkerProperties properties;
    private long tick;

    RunWorker(RunExecutionService runExecutionService, ToolExecutionWorker toolExecutionWorker,
              RunExecutionStore executionStore, RunWorkerProperties properties) {
        this.runExecutionService = runExecutionService;
        this.toolExecutionWorker = toolExecutionWorker;
        this.executionStore = executionStore;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${haizhuo.brain.run-worker.poll-delay-ms:1000}")
    void poll() {
        String workerId = properties.workerId();
        Duration leaseTtl = Duration.ofSeconds(properties.leaseTtlSeconds());
        if (++tick % properties.reclaimEveryTicks() == 0) {
            int reclaimed = executionStore.reclaimExpiredLeases();
            if (reclaimed > 0) {
                log.info("run.worker.reclaimed count={} workerId={}", reclaimed, workerId);
            }
        }
        while (toolExecutionWorker.executeNext(workerId)) {
            // 排空工具队列；每个完成都可能让某个 Run 重新排队
        }
        while (runExecutionService.executeNext(workerId, leaseTtl)) {
            // 排空 Run 队列
        }
    }
}
