package com.haizhuo.brain.runtime.api;

/** Native execution occurred but its durable outcome cannot be confirmed; do not turn into a tool reply. */
public final class DelegationPersistenceException extends RuntimeException {
    public DelegationPersistenceException(Throwable cause) {
        super("Child result persistence requires recovery verification", cause);
    }
}
