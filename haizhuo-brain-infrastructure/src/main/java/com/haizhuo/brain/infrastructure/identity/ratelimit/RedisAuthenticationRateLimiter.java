package com.haizhuo.brain.infrastructure.identity.ratelimit;

import com.haizhuo.brain.security.identity.AuthenticationRejectedException;
import com.haizhuo.brain.security.identity.AuditValueHasher;
import com.haizhuo.brain.security.identity.ratelimit.AuthenticationRateLimiter;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 基于 Redis 的固定窗口限流；键里放的是 SHA-256 摘要，而不是手机号或令牌原文。 */
@Component
@ConditionalOnProperty(prefix = "haizhuo.brain.security.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisAuthenticationRateLimiter implements AuthenticationRateLimiter {
    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final StringRedisTemplate redis;

    public RedisAuthenticationRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void checkLoginAllowed(String mobile, String source) {
        check("login", mobile, source);
    }

    @Override
    public void loginFailed(String mobile, String source) {
        recordFailure("login", mobile, source);
    }

    @Override
    public void loginSucceeded(String mobile, String source) {
        clear("login", mobile, source);
    }

    @Override
    public void checkActivationAllowed(String activationCredential, String source) {
        check("activation", activationCredential, source);
    }

    @Override
    public void activationFailed(String activationCredential, String source) {
        recordFailure("activation", activationCredential, source);
    }

    @Override
    public void activationSucceeded(String activationCredential, String source) {
        clear("activation", activationCredential, source);
    }

    private void check(String operation, String subject, String source) {
        if (attempts(key(operation, "subject", subject)) >= MAX_FAILURES || attempts(key(operation, "source", source)) >= MAX_FAILURES) {
            throw new AuthenticationRejectedException();
        }
    }

    private void recordFailure(String operation, String subject, String source) {
        increment(key(operation, "subject", subject));
        increment(key(operation, "source", source));
    }

    private void clear(String operation, String subject, String source) {
        redis.delete(java.util.List.of(key(operation, "subject", subject), key(operation, "source", source)));
    }

    private long attempts(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new AuthenticationRejectedException();
        }
    }

    private void increment(String key) {
        Long value = redis.opsForValue().increment(key);
        if (value != null && value == 1) redis.expire(key, WINDOW);
    }

    private static String key(String operation, String dimension, String value) {
        return "haizhuo:auth:rate:" + operation + ':' + dimension + ':' + AuditValueHasher.sha256(value);
    }
}
