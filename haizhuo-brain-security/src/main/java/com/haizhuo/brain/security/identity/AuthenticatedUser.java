package com.haizhuo.brain.security.identity;

import com.haizhuo.brain.kernel.identity.UserId;
import java.util.Set;

/** Trusted request identity built on the server after the account and current roles are verified. */
public record AuthenticatedUser(UserId userId, Set<PlatformRole> roles, long authVersion,
                                boolean mustChangePassword) {
    public AuthenticatedUser {
        if (userId == null) throw new IllegalArgumentException("userId is required");
        roles = Set.copyOf(roles);
        if (authVersion < 1) throw new IllegalArgumentException("authVersion must be positive");
    }

    public boolean hasRole(PlatformRole role) {
        return roles.contains(role);
    }
}
