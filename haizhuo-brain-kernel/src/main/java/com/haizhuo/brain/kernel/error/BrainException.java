package com.haizhuo.brain.kernel.error;

public class BrainException extends RuntimeException {
    private final ErrorCode code;

    public BrainException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
