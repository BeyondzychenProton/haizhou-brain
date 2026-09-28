package com.haizhuo.brain.security.identity;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.security.identity.port.PlatformIdentityRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrator-only account operations. Role/status changes always invalidate existing sessions. */
@Service
public class PlatformUserManagementService {
    private static final Duration ACTIVATION_TTL = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformIdentityRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public PlatformUserManagementService(PlatformIdentityRepository repository, PasswordEncoder passwordEncoder, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public CreatedPendingUser createPendingUser(UserId actorUserId, String mobile, String reason) {
        requireReason(reason);
        String normalized = MobileNormalizer.normalizeChinaMainland(mobile);
        if (repository.findByMobile(normalized).isPresent()) throw new IllegalStateException("手机号已存在");
        Instant now = clock.instant();
        PlatformUser user = repository.createUser(normalized, null, PlatformUserStatus.PENDING_ACTIVATION, false, actorUserId, now);
        repository.grantRole(user.id(), PlatformRole.USER, actorUserId, now);
        ActivationCredential credential = issueActivation(user.id(), actorUserId, now);
        repository.appendAudit(new PlatformUserAudit(actorUserId, user.id(), "USER_CREATED", null,
                PlatformIdentityService.state(user), reason.trim(), now));
        return new CreatedPendingUser(user, credential);
    }

    @Transactional
    public ActivationCredential reissueActivation(UserId actorUserId, UserId targetUserId, String reason) {
        requireReason(reason);
        PlatformUser target = repository.findByIdForUpdate(targetUserId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (target.status() != PlatformUserStatus.PENDING_ACTIVATION) {
            throw new IllegalStateException("只有待激活用户可以重发激活凭据");
        }
        Instant now = clock.instant();
        ActivationCredential credential = issueActivation(targetUserId, actorUserId, now);
        repository.appendAudit(new PlatformUserAudit(actorUserId, targetUserId, "ACTIVATION_REISSUED", null, null, reason.trim(), now));
        return credential;
    }

    @Transactional
    public void grantPlatformAdmin(UserId actorUserId, UserId targetUserId, String reason) {
        requireReason(reason);
        repository.lockAdministration();
        PlatformUser target = repository.findByIdForUpdate(targetUserId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!target.isActive()) throw new IllegalStateException("只能向已激活用户授予管理员角色");
        if (repository.findRoles(targetUserId).contains(PlatformRole.PLATFORM_ADMIN)) return;
        Instant now = clock.instant();
        repository.grantRole(targetUserId, PlatformRole.PLATFORM_ADMIN, actorUserId, now);
        incrementAuthVersion(target, now);
        repository.appendAudit(new PlatformUserAudit(actorUserId, targetUserId, "ADMIN_ROLE_GRANTED", "{\"admin\":false}",
                "{\"admin\":true}", reason.trim(), now));
    }

    @Transactional
    public void revokePlatformAdmin(UserId actorUserId, UserId targetUserId, String reason) {
        requireReason(reason);
        repository.lockAdministration();
        PlatformUser target = repository.findByIdForUpdate(targetUserId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!repository.findRoles(targetUserId).contains(PlatformRole.PLATFORM_ADMIN)) return;
        if (target.isActive() && repository.countActiveAdministrators() <= 1) {
            throw new IllegalStateException("不能撤销最后一名有效管理员");
        }
        Instant now = clock.instant();
        repository.revokeRole(targetUserId, PlatformRole.PLATFORM_ADMIN);
        incrementAuthVersion(target, now);
        repository.appendAudit(new PlatformUserAudit(actorUserId, targetUserId, "ADMIN_ROLE_REVOKED", "{\"admin\":true}",
                "{\"admin\":false}", reason.trim(), now));
    }

    @Transactional
    public void changeStatus(UserId actorUserId, UserId targetUserId, PlatformUserStatus requestedStatus, String reason) {
        requireReason(reason);
        if (requestedStatus == PlatformUserStatus.PENDING_ACTIVATION) throw new IllegalArgumentException("不允许通过管理接口改为待激活状态");
        repository.lockAdministration();
        PlatformUser target = repository.findByIdForUpdate(targetUserId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (target.status() == PlatformUserStatus.PENDING_ACTIVATION || target.passwordHash() == null) {
            throw new IllegalStateException("待激活账号不能通过启停接口变更状态");
        }
        if (target.status() == requestedStatus) return;
        boolean targetIsAdmin = repository.findRoles(targetUserId).contains(PlatformRole.PLATFORM_ADMIN);
        if (requestedStatus == PlatformUserStatus.DISABLED && target.isActive() && targetIsAdmin
                && repository.countActiveAdministrators() <= 1) {
            throw new IllegalStateException("不能停用最后一名有效管理员");
        }
        Instant now = clock.instant();
        if (!repository.updateStatus(targetUserId, target.authVersion(), requestedStatus, now)) {
            throw new IllegalStateException("账号状态已变化，请重试");
        }
        repository.appendAudit(new PlatformUserAudit(actorUserId, targetUserId, "USER_STATUS_CHANGED",
                PlatformIdentityService.state(target), "{\"status\":\"" + requestedStatus + "\"}", reason.trim(), now));
    }

    /**
     * 管理员视角的用户名单。角色一次性批量取回避免 N+1；手机号按统一口径脱敏，
     * 且返回结构里不含任何凭据字段。
     */
    @Transactional(readOnly = true)
    public UserDirectoryPage list(String keyword, PlatformUserStatus status, int limit, int offset) {
        List<PlatformUser> users = repository.searchUsers(keyword, status, limit, Math.max(offset, 0));
        Map<UserId, Set<PlatformRole>> roles = repository.findRoles(users.stream().map(PlatformUser::id).toList());
        List<UserDirectoryEntry> content = users.stream()
                .map(user -> new UserDirectoryEntry(user.id().value(), maskMobile(user.mobileNormalized()), user.status(),
                        roles.getOrDefault(user.id(), Set.of()).stream().map(Enum::name).sorted().toList()))
                .toList();
        return new UserDirectoryPage(content, repository.countUsers(keyword, status));
    }

    public void activate(String rawCredential, String newPassword) {
        activate(rawCredential, newPassword, "unknown");
    }

    @Transactional(noRollbackFor = AuthenticationRejectedException.class)
    public void activate(String rawCredential, String newPassword, String source) {
        PlatformIdentityService.validateNewPassword(newPassword);
        Instant now = clock.instant();
        String[] parts = rawCredential == null ? new String[0] : rawCredential.split("\\.", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw activationFailure(rawCredential, source, "INVALID_CREDENTIAL", now);
        }
        ActivationToken token = repository.findActivationBySelectorForUpdate(parts[0])
                .orElseThrow(() -> activationFailure(rawCredential, source, "INVALID_CREDENTIAL", now));
        if (!token.usableAt(now) || !passwordEncoder.matches(parts[1], token.tokenHash())) {
            throw activationFailure(rawCredential, source, "INVALID_OR_EXPIRED", now);
        }
        if (!repository.consumeActivationToken(token.id(), token.userId(), passwordEncoder.encode(newPassword), now)) {
            throw activationFailure(rawCredential, source, "ACCOUNT_STATE_CHANGED", now);
        }
        repository.appendAudit(new PlatformUserAudit(null, token.userId(), "ACCOUNT_ACTIVATED", null,
                "{\"status\":\"ACTIVE\"}", "一次性激活凭据已使用", now));
        repository.appendAuthenticationAudit("ACTIVATION_SUCCEEDED", AuditValueHasher.sha256(rawCredential),
                AuditValueHasher.sha256(source), null, now);
    }

    /** Called only by the explicit offline bootstrap command. */
    @Transactional
    public PlatformUser initializeFirstAdmin(String mobile, String initialPassword) {
        PlatformIdentityService.validateNewPassword(initialPassword);
        repository.lockAdministration();
        if (repository.countAdministratorRoles() > 0) throw new IllegalStateException("已有管理员，拒绝重复初始化");
        String normalized = MobileNormalizer.normalizeChinaMainland(mobile);
        if (repository.findByMobile(normalized).isPresent()) throw new IllegalStateException("手机号已存在");
        Instant now = clock.instant();
        PlatformUser user = repository.createUser(normalized, passwordEncoder.encode(initialPassword), PlatformUserStatus.ACTIVE, true, null, now);
        repository.grantRole(user.id(), PlatformRole.PLATFORM_ADMIN, null, now);
        repository.appendAudit(new PlatformUserAudit(null, user.id(), "INITIAL_ADMIN_CREATED", null,
                "{\"status\":\"ACTIVE\",\"role\":\"PLATFORM_ADMIN\",\"mustChangePassword\":true}",
                "离线初始化", now));
        return user;
    }

    private ActivationCredential issueActivation(UserId userId, UserId actorUserId, Instant now) {
        repository.revokeUnusedActivationTokens(userId, now);
        String selector = randomToken(12);
        String secret = randomToken(32);
        Instant expiresAt = now.plus(ACTIVATION_TTL);
        repository.createActivationToken(userId, selector, passwordEncoder.encode(secret), expiresAt, actorUserId, now);
        return new ActivationCredential(selector + "." + secret, expiresAt);
    }

    private void incrementAuthVersion(PlatformUser target, Instant now) {
        if (!repository.updateStatus(target.id(), target.authVersion(), target.status(), now)) {
            throw new IllegalStateException("账号状态已变化，请重试");
        }
    }

    private static String randomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 7) return "***";
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new IllegalArgumentException("操作原因不能为空且不能超过 500 个字符");
        }
    }

    private AuthenticationRejectedException activationFailure(String credential, String source, String category, Instant now) {
        repository.appendAuthenticationAudit("ACTIVATION_FAILED", AuditValueHasher.sha256(credential),
                AuditValueHasher.sha256(source), category, now);
        return new AuthenticationRejectedException();
    }

    public record CreatedPendingUser(PlatformUser user, ActivationCredential activationCredential) {
    }

    /** 名单里的一行用户；roles 已排序，便于前端稳定展示。 */
    public record UserDirectoryEntry(long userId, String mobileMasked, PlatformUserStatus status, List<String> roles) {
    }

    public record UserDirectoryPage(List<UserDirectoryEntry> content, long total) {
    }
}
