package com.haizhuo.brain.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "haizhuo.brain.meeting")
public record MeetingProperties(boolean enabled) {
}
