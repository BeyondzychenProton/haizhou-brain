package com.haizhuo.brain.platform.run;

/** 精确模型连接绑定缺失或不一致时返回的安全错误。 */
public final class ModelConnectionBindingUnavailableException extends RuntimeException {
    public ModelConnectionBindingUnavailableException() {
        super("The frozen model connection binding is unavailable");
    }
}
