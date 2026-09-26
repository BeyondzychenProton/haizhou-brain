package com.haizhuo.brain.platform.employee;

import java.time.Instant;

/** 只记录参数摘要和判定结果，不持久化工具原始参数或凭据。 */
public record ToolInvocationAudit(String invocationId, String runId, String capabilityCode,
                                  String capabilityRevision, long userId, String businessAction,
                                  String argumentsHash, String decision, String resultStatus,
                                  String resultSummary, Instant createdAt) {
}
