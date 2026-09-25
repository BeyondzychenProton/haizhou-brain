package com.haizhuo.brain.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "haizhuo.brain.persistence")
public record PersistenceProperties(boolean enabled) {
}
