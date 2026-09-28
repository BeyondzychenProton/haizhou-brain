package com.haizhuo.brain.bootstrap.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisIndexedWebSession;

/** 部署 profile 下 Redis 是必需的；内存模式只为隔离测试而存在。 */
@Configuration
@ConditionalOnProperty(prefix = "haizhuo.brain.security.session", name = "store", havingValue = "redis", matchIfMissing = true)
@EnableRedisIndexedWebSession
public class RedisSessionConfiguration {
}
