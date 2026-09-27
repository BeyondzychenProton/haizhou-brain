package com.haizhuo.brain.security.identity;

/** Deliberately generic: callers must not learn whether a mobile, password, or account status failed. */
public class AuthenticationRejectedException extends RuntimeException {
    public AuthenticationRejectedException() {
        super("认证失败或会话已失效");
    }
}
