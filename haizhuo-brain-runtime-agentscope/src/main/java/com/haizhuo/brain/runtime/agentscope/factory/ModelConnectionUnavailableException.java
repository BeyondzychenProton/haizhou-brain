package com.haizhuo.brain.runtime.agentscope.factory;

/** 精确模型连接无法解析时使用的安全异常，不包含任何凭据细节。 */
public final class ModelConnectionUnavailableException extends IllegalStateException {
    public ModelConnectionUnavailableException(String message) {
        super(message);
    }
}
