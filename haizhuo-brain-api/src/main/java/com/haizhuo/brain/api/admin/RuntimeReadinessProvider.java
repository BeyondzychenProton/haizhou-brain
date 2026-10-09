package com.haizhuo.brain.api.admin;

import java.time.Instant;
import java.util.List;

/** 只读报告：列出已配置门槛及部署验证证据。 */
public interface RuntimeReadinessProvider {
    ReadinessResponse readiness();

    record ReadinessResponse(int schemaVersion, List<GateStatus> items, String nextCursor, boolean hasMore) {
        public ReadinessResponse {
            items = List.copyOf(items);
        }
    }

    enum GateState { DESIGNED, IMPLEMENTED_CLOSED, VALIDATED, ENABLED, SUSPENDED }

    record GateStatus(String gateId, GateState state, boolean configured, boolean verificationMatched,
                      boolean enabled, String reasonCode, String verificationReasonCode,
                      List<String> requirements, Instant checkedAt) {
        public GateStatus {
            requirements = List.copyOf(requirements);
        }
    }
}
