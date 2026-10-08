package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;
import java.util.Objects;

/**
 * 子执行/未知来源的内部事件。不能直接送入主答案、根 Run 控制或用户 SSE。
 * text 只承载文本增量/最终消息；不携带思考链、工具参数或完整 metadata。
 */
public record AgentInternalEvent(RunId runId, AgentEventDescriptor descriptor,
                                 Kind kind, String text) implements BrainAgentEvent {
    public AgentInternalEvent {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(kind, "kind");
        if (descriptor.executionRole() == AgentExecutionRole.ROOT) {
            throw new IllegalArgumentException("Internal event must not control the root Run");
        }
    }

    public enum Kind { STARTED, ENDED, TEXT_DELTA, RESULT, STOPPED, EXTERNAL_TOOL_WAIT, TOOL_PROGRESS }
}
