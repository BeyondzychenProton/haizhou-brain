package com.haizhuo.brain.api.admin;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 当前服务配置与观测状态的只读快照，不包含秘密信息。 */
public interface OperationsOverviewProvider {
    OverviewResponse overview();

    record OverviewResponse(Instant observedAt, String instanceLabel, RuntimeSummary runtime,
                            List<WorkerSummary> workers, ObservabilitySummary observability,
                            List<ProtectedLink> links) {
        public OverviewResponse {
            workers = List.copyOf(workers);
            links = List.copyOf(links);
        }
    }

    record RuntimeSummary(String agentScopeVersion, boolean loaded, Map<String, Boolean> profileGates) {
        public RuntimeSummary {
            profileGates = Collections.unmodifiableMap(new LinkedHashMap<>(profileGates));
        }
    }

    record WorkerSummary(String kind, boolean configuredEnabled, boolean loaded,
                         String safeInstanceLabel, Long leaseTtlSeconds, Long reclaimEveryTicks,
                         Instant lastObservedAt) { }

    record ObservabilitySummary(boolean configuredEnabled, boolean loaded,
                                boolean captureContentEnabled, Instant lastProbeAt,
                                String probeStatus, String safeErrorCode, Integer scoreQueueDepth) { }

    /** 仅可返回由服务端配置且受部署策略保护的链接。 */
    record ProtectedLink(String kind, String label, String url) { }
}
