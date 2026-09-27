package com.haizhuo.brain.security.identity;

import java.io.Serial;
import java.io.Serializable;

/**
 * Minimal object persisted in the Redis WebSession. Roles and account state are intentionally not
 * cached here: they are loaded again before each protected request.
 */
public record PlatformSessionPrincipal(long userId, long authVersion) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public PlatformSessionPrincipal {
        if (userId <= 0 || authVersion < 1) throw new IllegalArgumentException("invalid session principal");
    }
}
