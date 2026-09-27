package com.haizhuo.brain.security.identity.port;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.security.identity.ActivationToken;
import com.haizhuo.brain.security.identity.PlatformRole;
import com.haizhuo.brain.security.identity.PlatformUser;
import com.haizhuo.brain.security.identity.PlatformUserAudit;
import com.haizhuo.brain.security.identity.PlatformUserStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** Persistence boundary for platform identity. Implementations must not return plaintext credentials. */
public interface PlatformIdentityRepository {
    Optional<PlatformUser> findByMobile(String mobileNormalized);

    Optional<PlatformUser> findById(UserId userId);

    Optional<PlatformUser> findByIdForUpdate(UserId userId);

    void lockAdministration();

    long countAdministratorRoles();

    long countActiveAdministrators();

    Set<PlatformRole> findRoles(UserId userId);

    PlatformUser createUser(String mobileNormalized, String passwordHash, PlatformUserStatus status,
                            boolean mustChangePassword, UserId createdBy, Instant now);

    boolean updatePassword(UserId userId, long expectedAuthVersion, String passwordHash,
                           PlatformUserStatus status, boolean mustChangePassword, Instant now);

    boolean updateStatus(UserId userId, long expectedAuthVersion, PlatformUserStatus status, Instant now);

    void grantRole(UserId userId, PlatformRole role, UserId grantedBy, Instant now);

    void revokeRole(UserId userId, PlatformRole role);

    void revokeUnusedActivationTokens(UserId userId, Instant now);

    ActivationToken createActivationToken(UserId userId, String selector, String tokenHash,
                                          Instant expiresAt, UserId createdBy, Instant now);

    Optional<ActivationToken> findActivationBySelectorForUpdate(String selector);

    boolean consumeActivationToken(long tokenId, UserId userId, String passwordHash, Instant now);

    void appendAudit(PlatformUserAudit audit);

    void appendAuthenticationAudit(String eventType, String subjectDigest, String sourceDigest,
                                   String failureCategory, Instant occurredAt);
}
