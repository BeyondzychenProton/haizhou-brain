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
