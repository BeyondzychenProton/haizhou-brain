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
        getRun(runId, owner);
        return store.findEvents(runId, owner, Math.max(afterSequence, 0), 200);
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
