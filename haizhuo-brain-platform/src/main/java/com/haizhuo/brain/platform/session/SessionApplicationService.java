package com.haizhuo.brain.platform.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundle;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleRepository;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.HarnessRunSpec;
import com.haizhuo.brain.platform.run.HarnessRunSpecFactory;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.SessionEvent;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.platform.run.SessionTimelineItem;
import com.haizhuo.brain.platform.run.RunGuidance;
import com.haizhuo.brain.kernel.identity.RunId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 只创建与读取属于当前已认证平台用户的会话。 */
public class SessionApplicationService {
    private static final TenantId DEFAULT_TENANT = new TenantId(1);
    private final SessionRunStore store;
    private final EmployeeCatalog employees;
    private final HarnessDefinitionBundleRepository bundles;
    private final HarnessRunSpecFactory runSpecFactory;
    private final Clock clock;

    public SessionApplicationService(SessionRunStore store, EmployeeCatalog employees,
                                     HarnessDefinitionBundleRepository bundles,
                                     HarnessRunSpecFactory runSpecFactory, Clock clock) {
        this.store = store;
        this.employees = employees;
        this.bundles = bundles;
        this.runSpecFactory = runSpecFactory;
        this.clock = clock;
    }

    public AgentSession create(UserId owner, long employeeId) {
        employees.findPublished(DEFAULT_TENANT, employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Published employee was not found"));
        Instant now = clock.instant();
        return store.createSession(new AgentSession(SessionId.newId(), owner, employeeId,
                AgentSession.Status.ACTIVE, now, now, 0));
    }

    public AgentSession get(SessionId sessionId, UserId owner) {
        return store.findSession(sessionId, owner)
                .orElseThrow(() -> new IllegalArgumentException("Session was not found"));
    }

    public List<AgentSession> list(UserId owner, int limit) { return store.findSessions(owner, limit); }

    public AgentRun createRun(SessionId sessionId, UserId owner, String clientRequestId, String input) {
        AgentSession session = get(sessionId, owner);
        var published = employees.findPublished(DEFAULT_TENANT, session.employeeId()).orElseThrow(() -> new IllegalStateException("Session employee is no longer published"));
        // 能力包在发布时冻结；缺少能力包的定义属于 Harness 运行时之前的产物，
        // 必须重新发布后才能运行（规格 §8/§10.2）。
        HarnessDefinitionBundle bundle = bundles.findByDefinitionVersionId(published.definition().id())
                .orElseThrow(() -> new IllegalStateException("Published definition has no runtime bundle; re-publish the employee first"));
        Instant now = clock.instant();
        RunId runId = RunId.newId();
        HarnessRunSpec runSpec = runSpecFactory.create(runId, owner, bundle, null);
        return store.createRun(new AgentRun(runId, session.id(), owner, session.employeeId(), published.definition().id(), clientRequestId, digest(input), RunState.QUEUED, now, null, null), input, runSpec);
    }

    public AgentRun getRun(RunId runId, UserId owner) { return store.findRun(runId, owner).orElseThrow(() -> new IllegalArgumentException("Run was not found")); }
    public List<AgentRun> runs(SessionId sessionId, UserId owner, int limit) {
        get(sessionId, owner);
        return store.findRuns(sessionId, owner, limit);
    }
    public java.util.List<RunEvent> events(RunId runId, UserId owner, int afterSequence) {
        return events(runId, owner, afterSequence, 200);
    }
    public java.util.List<RunEvent> events(RunId runId, UserId owner, int afterSequence, int limit) {
        getRun(runId, owner);
        return findEvents(runId, owner, afterSequence, limit);
    }
    /**
     * 归属已在本次 SSE 订阅建立时校验过，轮询补读不再重复加载 run 行。
     * 仓储查询本身仍按 owner 过滤，所以跳过校验不会放宽可见性；代价是 run 行被移除时
     * 该流只会静默返回空批次，终止判定交给前端的状态刷新而不是本方法。
     */
    public java.util.List<RunEvent> eventsOfOwnedRun(RunId runId, UserId owner, int afterSequence, int limit) {
        return findEvents(runId, owner, afterSequence, limit);
    }
    private java.util.List<RunEvent> findEvents(RunId runId, UserId owner, int afterSequence, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        return store.findEvents(runId, owner, Math.max(afterSequence, 0), boundedLimit);
    }
    /**
     * 会话级持久事件（P2）：sessionCursor 在同一 Session 内严格递增，是会话流与历史分页的
     * 唯一续传游标。超过保留窗口或游标失效时返回空批次，客户端应转快照恢复。
     */
    public java.util.List<SessionEvent> sessionEvents(SessionId sessionId, UserId owner, long afterCursor, int limit) {
        get(sessionId, owner);
        return findSessionEvents(sessionId, owner, afterCursor, limit);
    }
    /** 归属已在本次订阅建立时校验过；轮询补读不再重复加载 session 行（仓储仍按 owner 过滤）。 */
    public java.util.List<SessionEvent> sessionEventsOfOwnedSession(SessionId sessionId, UserId owner,
                                                                    long afterCursor, int limit) {
        return findSessionEvents(sessionId, owner, afterCursor, limit);
    }
    private java.util.List<SessionEvent> findSessionEvents(SessionId sessionId, UserId owner, long afterCursor, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        return store.findSessionEvents(sessionId, owner, Math.max(afterCursor, 0), boundedLimit);
    }
    public List<SessionTimelineItem> timeline(SessionId sessionId, UserId owner, int limit) {
        get(sessionId, owner);
        return store.findTimeline(sessionId, owner, limit);
    }
    public int queuePosition(RunId runId, UserId owner) { getRun(runId, owner); return store.queuePosition(runId, owner); }
    public AgentRun cancel(RunId runId, UserId owner) { return store.cancel(runId, owner); }
    public RunGuidance guide(RunId runId, UserId owner, String content) {
        getRun(runId, owner);
        return store.addGuidance(runId, owner, "USER", content.trim());
    }

    private static String digest(String input) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
}
