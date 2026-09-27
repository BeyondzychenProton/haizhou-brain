package com.haizhuo.brain.bootstrap.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisIndexedWebSession;

/** Redis is mandatory in deployed profiles; the in-memory mode exists only for isolated tests. */
@Configuration
@ConditionalOnProperty(prefix = "haizhuo.brain.security.session", name = "store", havingValue = "redis", matchIfMissing = true)
@EnableRedisIndexedWebSession
public class RedisSessionConfiguration {
}
