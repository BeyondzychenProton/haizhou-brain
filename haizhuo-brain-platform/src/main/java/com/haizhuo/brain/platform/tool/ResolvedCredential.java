package com.haizhuo.brain.platform.tool;

import java.util.Objects;
import java.util.Optional;

/**
 * 进程内的凭据材料。secret() 绝不进入任何持久化记录；toString
 * 只暴露引用（I-08/§40）。
 */
public record ResolvedCredential(String reference, Optional<String> secret) {
    public ResolvedCredential {
        Objects.requireNonNull(reference);
        secret = secret == null ? Optional.empty() : secret;
    }

    public static ResolvedCredential none() {
        return new ResolvedCredential("none", Optional.empty());
    }

    @Override
    public String toString() {
        return "ResolvedCredential{reference=" + reference + "}";
    }
}
