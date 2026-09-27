package com.haizhuo.brain.security.identity;

import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;

/** Audit snapshots contain account state only and must not include passwords, session IDs, or token values. */
public record PlatformUserAudit(UserId actorUserId, UserId targetUserId, String eventType,
                                String previousState, String newState, String reason, Instant occurredAt) {
}
