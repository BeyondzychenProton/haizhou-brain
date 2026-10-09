package com.haizhuo.brain.platform.employee;

import java.util.List;
import java.util.Objects;

/** 管理端历史修订的有界游标页。 */
public record CapabilityAssetRevisionPage(List<CapabilityAssetRevisionSummary> items,
                                          String nextCursor, boolean hasMore) {
    public CapabilityAssetRevisionPage {
        items = List.copyOf(Objects.requireNonNull(items));
        if (hasMore && (nextCursor == null || nextCursor.isBlank()))
            throw new IllegalArgumentException("A next cursor is required when more revisions exist");
        if (!hasMore && nextCursor != null)
            throw new IllegalArgumentException("The next cursor must be absent on the final page");
    }
}
