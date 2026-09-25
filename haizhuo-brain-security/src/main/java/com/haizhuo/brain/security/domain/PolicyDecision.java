package com.haizhuo.brain.security.domain;

public record PolicyDecision(Decision decision, String reason) {
    public enum Decision {ALLOW, APPROVE, DENY}
}
