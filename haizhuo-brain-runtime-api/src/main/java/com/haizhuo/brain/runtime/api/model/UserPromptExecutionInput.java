package com.haizhuo.brain.runtime.api.model;

import java.util.Objects;

/** 新建 Run 时的首条用户消息（规格 §7.5/§31）。 */
public record UserPromptExecutionInput(String content) implements AgentExecutionInput {
    public UserPromptExecutionInput {
        Objects.requireNonNull(content);
    }
}
