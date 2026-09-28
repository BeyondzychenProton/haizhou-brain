package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import java.time.Instant;

/**
 * 运行期间不落库的用户可见实时事件。它只用于降低交互延迟；最终文本和运行状态仍以
 * {@link RunEvent} 为准，客户端断线后不得依赖该事件恢复完整答案。streamOffset 仅在
 * attemptId 对应的单次运行尝试内递增。
 */
public record RunRealtimeEvent(RunId runId, String attemptId, long streamOffset, String type,
                               String content, String messageId, String blockId, Instant occurredAt) {
}
