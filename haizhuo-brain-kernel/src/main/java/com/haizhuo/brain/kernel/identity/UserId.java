package com.haizhuo.brain.kernel.identity;

public record UserId(long value) {
    public UserId {
        if (value <= 0) throw new IllegalArgumentException("userId must be positive");
    }
}
