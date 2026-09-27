package com.haizhuo.brain.bootstrap.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("haizhuo.brain.security.session")
public record SecuritySessionProperties(boolean secureCookie, String sameSite) {
    public SecuritySessionProperties {
        if (sameSite == null || sameSite.isBlank()) sameSite = "Lax";
    }
}
