package com.haizhuo.brain.api.error;

/** Stable, credential-safe error envelope for HTTP clients. */
public record ApiError(String code, String message) {
}
