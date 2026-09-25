package com.haizhuo.brain.kernel.identity;

public record TenantId(long value) {
    public TenantId {
        if (value <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }
}
