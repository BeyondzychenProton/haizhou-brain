package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.session.AgentSession;
import java.util.Optional;
import java.util.List;

/** 事务性持久化边界。每次查询都必须带上已认证的属主。 */
public interface SessionRunStore {
    AgentSession createSession(AgentSession session);

    Optional<AgentSession> findSession(SessionId sessionId, UserId owner);
    default List<AgentSession> findSessions(UserId owner, int limit) { return List.of(); }

    /**
     * 原子地认领属主 Session 上唯一的活动 Run 槽位，或返回一次幂等重放。
     * 在同一事务内冻结 RunSpec（定义能力包 + 工具视图）（规格 §10.2）；
     * 幂等重放会跳过 spec 插入。
     */
    AgentRun createRun(AgentRun run, String input, HarnessRunSpec runSpec);

    Optional<AgentRun> findRun(RunId runId, UserId owner);
    default List<AgentRun> findRuns(SessionId sessionId, UserId owner, int limit) { return List.of(); }

    List<RunEvent> findEvents(RunId runId, UserId owner, int afterSequence, int limit);

    /** 会话级投影：按 sessionCursor 严格递增返回属主 Session 的持久事件。 */
    default List<SessionEvent> findSessionEvents(SessionId sessionId, UserId owner, long afterCursor, int limit) {
        return List.of();
    }

    /** 会话投影当前保留下界（最小 sessionCursor）；返回 0 表示从未裁剪，任何游标都不会失效。 */
    default long oldestSessionCursor(SessionId sessionId, UserId owner) { return 0; }

    /**
     * 按保留窗口裁剪会话投影，只保留最新 {@code keepLatest} 条，返回实际删除条数。
     * 裁剪只发生在客户端已确认游标之后的历史上；调用方必须保证 keepLatest 大于
     * 任何仍在续传的客户端游标，否则这些客户端会收到游标失效并转全量恢复。
     */
    default int trimSessionEvents(SessionId sessionId, UserId owner, int keepLatest) { return 0; }

    default List<SessionTimelineItem> findTimeline(SessionId sessionId, UserId owner, int limit) { return List.of(); }

    default int queuePosition(RunId runId, UserId owner) { return 0; }

    /**
     * 用户发起的取消（规格 §47）：QUEUED → CANCELLED；RUNNING → CANCELLING
     * （运行时在下一个安全检查点确认）；WAITING_TOOL/WAITING_CONFIRMATION →
     * 关闭未决的工具工作并取消该 Run。已处于终态的 Run 拒绝该请求。
     */
    default AgentRun cancel(RunId runId, UserId owner) { throw new UnsupportedOperationException("Run cancellation is not available"); }

    default RunGuidance addGuidance(RunId runId, UserId owner, String source, String content) {
        throw new UnsupportedOperationException("Run guidance is not available");
    }
}
