package com.haizhuo.brain.security.identity.ratelimit;

/**
 * Distributed throttle boundary for anonymous credential endpoints. Implementations must never use
 * raw passwords or activation credentials as a persistence key.
 */
public interface AuthenticationRateLimiter {
    void checkLoginAllowed(String mobile, String source);

    void loginFailed(String mobile, String source);

    void loginSucceeded(String mobile, String source);

    void checkActivationAllowed(String activationCredential, String source);

    void activationFailed(String activationCredential, String source);

    void activationSucceeded(String activationCredential, String source);
}
