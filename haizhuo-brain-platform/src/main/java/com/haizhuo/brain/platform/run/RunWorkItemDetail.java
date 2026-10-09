package com.haizhuo.brain.platform.run;

import java.util.List;

public record RunWorkItemDetail(String runId, RunProgressProjection.WorkItemSummary item,
                                List<RunProgressProjection.RevisionSummary> revisions) {
    public RunWorkItemDetail {
        revisions = List.copyOf(revisions == null ? List.of() : revisions);
    }
}
