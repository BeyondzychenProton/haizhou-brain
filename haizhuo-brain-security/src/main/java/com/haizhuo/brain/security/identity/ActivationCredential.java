package com.haizhuo.brain.security.identity;

import java.time.Instant;

/** Plain token exists only in the create/reissue response. Never persist or log it. */
public record ActivationCredential(String token, Instant expiresAt) {
}
