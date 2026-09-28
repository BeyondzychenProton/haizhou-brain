package com.haizhuo.brain.bootstrap.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 持久化 worker 的调参项（规格 §14.1/§86）。模型执行是显式开启的：
 * 部署配置必须明确启用它。leaseTtlSeconds 是 attempt 租约时长；心跳按 ttl/3 的频率触发。
 * workerId 默认取进程运行时名（pid@host）。
 */
@ConfigurationProperties(prefix = "haizhuo.brain.run-worker")
public record RunWorkerProperties(boolean enabled, String workerId, long leaseTtlSeconds,
                                  long reclaimEveryTicks) {
    public RunWorkerProperties {
        if (workerId == null || workerId.isBlank()) {
            workerId = "worker-" + java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        }
        if (leaseTtlSeconds <= 0) {
            leaseTtlSeconds = 120;
        }
        if (reclaimEveryTicks <= 0) {
            reclaimEveryTicks = 10;
        }
    }
}
