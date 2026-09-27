package com.haizhuo.brain.security.identity;

import com.haizhuo.brain.kernel.identity.UserId;
import java.util.Objects;

/** Internal account aggregate. Its password hash must never cross into an API response or audit body. */
public record PlatformUser(UserId id, String mobileNormalized, String passwordHash,
                           PlatformUserStatus status, long authVersion, boolean mustChangePassword) {
    public PlatformUser {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mobileNormalized, "mobileNormalized");
        Objects.requireNonNull(status, "status");
        if (authVersion < 1) throw new IllegalArgumentException("authVersion must be positive");
    }

    public boolean isActive() {
        return status == PlatformUserStatus.ACTIVE;
    }
}
