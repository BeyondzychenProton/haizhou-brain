package com.haizhuo.brain.security.identity.port;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.security.identity.ActivationToken;
import com.haizhuo.brain.security.identity.PlatformRole;
import com.haizhuo.brain.security.identity.PlatformUser;
import com.haizhuo.brain.security.identity.PlatformUserAudit;
import com.haizhuo.brain.security.identity.PlatformUserStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** 一次取多个用户的角色，供列表场景使用，避免逐行查询造成 N+1。 */
    Map<UserId, Set<PlatformRole>> findRoles(Collection<UserId> userIds);

    /** 管理员视角的分页检索；keyword 为空白时不做关键词过滤，status 为 null 时不做状态过滤。 */
    List<PlatformUser> searchUsers(String keyword, PlatformUserStatus status, int limit, int offset);

    /** 与 {@link #searchUsers} 完全相同的过滤条件，用于分页总数。 */
    long countUsers(String keyword, PlatformUserStatus status);

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
