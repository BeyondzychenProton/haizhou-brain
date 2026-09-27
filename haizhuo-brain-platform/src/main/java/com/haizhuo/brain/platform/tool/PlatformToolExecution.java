package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次企业副作用的持久化记录（规格 §6.8/§45.1）。在 (runId, toolUseId)
 * 与 idempotencyKey 上唯一。绝不保存凭据或供应商原始报文。
 */
public record PlatformToolExecution(String id, RunId runId, String toolUseId, long capabilityRevisionId,
                                    String toolName, String inputJson, String inputDigest,
                                    ToolExecutionState state, String idempotencyKey, String externalReceipt,
                                    String resultJson, String resultDigest, ResultDeliveryState resultDeliveryState,
                                    String errorCode, Instant createdAt, Instant updatedAt) {
    public PlatformToolExecution {
        Objects.requireNonNull(id);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(toolUseId);
        if (capabilityRevisionId <= 0) throw new IllegalArgumentException("capabilityRevisionId must be positive");
        Objects.requireNonNull(toolName);
        Objects.requireNonNull(inputJson);
        Objects.requireNonNull(inputDigest);
        Objects.requireNonNull(state);
        Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
    }
}
