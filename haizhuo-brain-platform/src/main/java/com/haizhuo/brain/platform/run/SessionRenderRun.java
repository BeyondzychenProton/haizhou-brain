package com.haizhuo.brain.platform.run;

import java.time.Instant;

public record SessionRenderRun(String runId, String state, long definitionVersionId, Instant createdAt,
                              Instant startedAt, Instant finishedAt) { }
