package com.haizhuo.brain.runtime.api.model;

import com.haizhuo.brain.kernel.identity.RunId;

public record AgentRunSnapshot(RunId runId, String status, String content) {
}
