package com.haizhuo.brain.platform.run;

import java.util.List;

public record RunWorkItemPage(List<RunProgressProjection.WorkItemSummary> items,
                              String nextCursor, boolean hasMore) {
    public RunWorkItemPage {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
