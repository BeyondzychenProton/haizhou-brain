package com.haizhuo.brain.security.port;

public interface AuthorizationRepository {
    boolean isAllowed(long userId, long tenantId, String resource, String action);
}
