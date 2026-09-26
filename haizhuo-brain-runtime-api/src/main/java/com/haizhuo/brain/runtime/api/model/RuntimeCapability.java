package com.haizhuo.brain.runtime.api.model;

/** Exact capability revision allowed for this Run. */
public record RuntimeCapability(String type, String referenceId, String revision) {
}
