package com.haizhuo.brain.bootstrap.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Model execution is opt-in: deployment configuration must explicitly enable it. */
@ConfigurationProperties(prefix = "haizhuo.brain.run-worker")
public record RunWorkerProperties(boolean enabled) { }
