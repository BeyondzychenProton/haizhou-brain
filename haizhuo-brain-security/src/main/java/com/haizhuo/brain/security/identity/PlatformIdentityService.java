package com.haizhuo.brain.security.identity;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.security.identity.port.PlatformIdentityRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account authentication and password lifecycle. This service has no HTTP or Redis dependency. */
@Service
public class PlatformIdentityService {
    private final PlatformIdentityRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public PlatformIdentityService(PlatformIdentityRepository repository, PasswordEncoder passwordEncoder, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public AuthenticatedUser authenticate(String mobile, String password) {
        return authenticate(mobile, password, "unknown");
    }

    @Transactional(noRollbackFor = AuthenticationRejectedException.class)
    public AuthenticatedUser authenticate(String mobile, String password, String source) {
        Instant now = clock.instant();
        String subjectDigest = AuditValueHasher.sha256(mobile);
        String sourceDigest = AuditValueHasher.sha256(source);
        String normalized;
        try {
            normalized = MobileNormalizer.normalizeChinaMainland(mobile);
        } catch (IllegalArgumentException ex) {
            throw loginFailure(subjectDigest, sourceDigest, "INVALID_MOBILE", now);
        }
        PlatformUser user = repository.findByMobile(normalized).orElse(null);
        if (user == null) throw loginFailure(subjectDigest, sourceDigest, "ACCOUNT_NOT_FOUND", now);
        if (!user.isActive()) throw loginFailure(subjectDigest, sourceDigest, "ACCOUNT_NOT_ACTIVE", now);
        if (password == null || user.passwordHash() == null || !passwordEncoder.matches(password, user.passwordHash())) {
            throw loginFailure(subjectDigest, sourceDigest, "PASSWORD_INVALID", now);
        }
        repository.appendAuthenticationAudit("LOGIN_SUCCEEDED", subjectDigest, sourceDigest, null, now);
        return authenticated(user, repository.findRoles(user.id()));
    }

    @Transactional(readOnly = true)
    public AuthenticatedUser refresh(UserId userId, long sessionAuthVersion) {
        PlatformUser user = repository.findById(userId).orElseThrow(AuthenticationRejectedException::new);
        if (!user.isActive() || user.authVersion() != sessionAuthVersion) throw new AuthenticationRejectedException();
        return authenticated(user, repository.findRoles(user.id()));
    }

    @Transactional(readOnly = true)
    public PlatformUser currentUser(UserId userId) {
        PlatformUser user = repository.findById(userId).orElseThrow(AuthenticationRejectedException::new);
        if (!user.isActive()) throw new AuthenticationRejectedException();
        return user;
    }

    @Transactional
    public AuthenticatedUser changePassword(UserId userId, String currentPassword, String newPassword) {
        validateNewPassword(newPassword);
        PlatformUser user = repository.findByIdForUpdate(userId).orElseThrow(AuthenticationRejectedException::new);
        if (!user.isActive() || user.passwordHash() == null || !passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new AuthenticationRejectedException();
        }
        Instant now = clock.instant();
        if (!repository.updatePassword(user.id(), user.authVersion(), passwordEncoder.encode(newPassword), user.status(), false, now)) {
            throw new IllegalStateException("账号状态已变化，请重新登录");
        }
        repository.appendAudit(new PlatformUserAudit(user.id(), user.id(), "PASSWORD_CHANGED",
                state(user), "{\"mustChangePassword\":false}", "用户自主修改密码", now));
        return new AuthenticatedUser(user.id(), repository.findRoles(user.id()), user.authVersion() + 1, false);
    }

    private static AuthenticatedUser authenticated(PlatformUser user, Set<PlatformRole> roles) {
        return new AuthenticatedUser(user.id(), roles, user.authVersion(), user.mustChangePassword());
    }

    static void validateNewPassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new IllegalArgumentException("密码长度必须为 12 到 128 个字符");
        }
    }

    static String state(PlatformUser user) {
        return "{\"status\":\"" + user.status() + "\",\"authVersion\":" + user.authVersion()
                + ",\"mustChangePassword\":" + user.mustChangePassword() + "}";
    }

    private AuthenticationRejectedException loginFailure(String subjectDigest, String sourceDigest, String category, Instant now) {
        repository.appendAuthenticationAudit("LOGIN_FAILED", subjectDigest, sourceDigest, category, now);
        return new AuthenticationRejectedException();
    }
}
