package com.haizhuo.brain.security.identity;

import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;

/** Persisted activation-token metadata; tokenHash protects the secret part of the credential. */
public record ActivationToken(long id, UserId userId, String selector, String tokenHash,
                              Instant expiresAt, Instant usedAt, Instant revokedAt) {
    public boolean usableAt(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}
