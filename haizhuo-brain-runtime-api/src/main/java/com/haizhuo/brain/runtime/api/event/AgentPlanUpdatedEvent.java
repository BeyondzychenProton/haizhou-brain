package com.haizhuo.brain.runtime.api.event;

import com.haizhuo.brain.kernel.identity.RunId;

/**
 * 计划快照更新（P2）：由 Harness 计划工具（plan_enter / plan_write / plan_exit）的调用派生。
 * 计划内容取自工具入参的流式增量；平台把它作为持久事件落库、并投影到会话游标，
 * 因此计划在断线后可恢复，并且始终归属于产生它的那个 Run，
 * 不会与切换定义版本后的新运行混在一起。
 *
 * <p>{@code plan} 在 ENTER/EXIT 阶段为 null：这两个阶段只表达计划模式的开启与提交，
 * 内容以最近一次 WRITE 为准。</p>
 */
public record AgentPlanUpdatedEvent(RunId runId, Phase phase, String plan) implements BrainAgentEvent {

    public enum Phase { ENTER, WRITE, EXIT }
}
