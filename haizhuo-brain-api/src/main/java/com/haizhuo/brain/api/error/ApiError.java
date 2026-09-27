package com.haizhuo.brain.api.error;

/** 面向 HTTP 客户端的、稳定且不泄漏凭据的错误信封。 */
public record ApiError(String code, String message) {
}
