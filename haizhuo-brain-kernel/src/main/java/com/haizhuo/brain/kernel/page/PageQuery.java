package com.haizhuo.brain.kernel.page;

public record PageQuery(long page, long size) {
    public PageQuery {
        if (page < 1 || size < 1 || size > 500) throw new IllegalArgumentException("invalid page");
    }
}
