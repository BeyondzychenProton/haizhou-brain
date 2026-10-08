package com.haizhuo.brain.platform.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundle;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleRepository;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeConfiguration;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.AgentResult;
import com.haizhuo.brain.platform.run.AgentResultRepository;
import com.haizhuo.brain.platform.run.HarnessRunSpec;
import com.haizhuo.brain.platform.run.HarnessRunSpecFactory;
import com.haizhuo.brain.platform.run.RunResultReference;
import com.haizhuo.brain.platform.run.RunExecutionMode;
import com.haizhuo.brain.platform.run.RunExecutionTarget;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.SessionEvent;
import com.haizhuo.brain.platform.run.SessionEventPage;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.SessionSnapshot;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.platform.run.SessionTimelineItem;
import com.haizhuo.brain.platform.run.RunGuidance;
import com.haizhuo.brain.kernel.identity.RunId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;

/** 只创建与读取属于当前已认证平台用户的会话。 */
public class SessionApplicationService {
    private static final TenantId DEFAULT_TENANT = new TenantId(1);
    private final SessionRunStore store;
    private final EmployeeCatalog employees;
    private final HarnessDefinitionBundleRepository bundles;
    private final HarnessRunSpecFactory runSpecFactory;
    private final AgentResultRepository results;
    private final Clock clock;

    private static final int MAX_REFERENCED_RESULTS = 8;
    private static final int MAX_REFERENCED_RESULT_BYTES = 512 * 1024;

    public SessionApplicationService(SessionRunStore store, EmployeeCatalog employees,
                                     HarnessDefinitionBundleRepository bundles,
                                     HarnessRunSpecFactory runSpecFactory, Clock clock) {
        this(store, employees, bundles, runSpecFactory, null, clock);
    }

    public SessionApplicationService(SessionRunStore store, EmployeeCatalog employees,
                                     HarnessDefinitionBundleRepository bundles,
                                     HarnessRunSpecFactory runSpecFactory, AgentResultRepository results,
                                     Clock clock) {
        this.store = store;
        this.employees = employees;
        this.bundles = bundles;
        this.runSpecFactory = runSpecFactory;
        this.results = results;
        this.clock = clock;
    }

    public AgentSession create(UserId owner, long employeeId) {
        var published = employees.findPublished(DEFAULT_TENANT, employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Published employee was not found"));
        Instant now = clock.instant();
        return store.createSession(new AgentSession(SessionId.newId(), owner, employeeId,
                AgentSession.Status.ACTIVE, now, now, 0, published.definition().id(), false));
    }

    public AgentSession get(SessionId sessionId, UserId owner) {
        return store.findSession(sessionId, owner)
                .orElseThrow(() -> new IllegalArgumentException("Session was not found"));
    }

    public List<AgentSession> list(UserId owner, int limit) { return store.findSessions(owner, limit); }

    public AgentRun createRun(SessionId sessionId, UserId owner, String clientRequestId, String input) {
        return createRun(sessionId, owner, clientRequestId, input, null, RunExecutionMode.DIRECT);
    }

    /** Resolve a user-visible role from the Session's frozen definition, never from client-supplied versions or keys. */
    public AgentRun createRun(SessionId sessionId, UserId owner, String clientRequestId, String input,
                              String targetRoleId, RunExecutionMode mode) {
        return createRun(sessionId, owner, clientRequestId, input, targetRoleId, mode, List.of());
    }

    public AgentRun createRun(SessionId sessionId, UserId owner, String clientRequestId, String input,
                              String targetRoleId, RunExecutionMode mode, List<String> referencedResultIds) {
        AgentSession session = get(sessionId, owner);
        if (session.status() != AgentSession.Status.ACTIVE)
            throw new IllegalStateException("Session is closed");
        RunExecutionMode executionMode = mode == null ? RunExecutionMode.DIRECT : mode;
        if (executionMode != RunExecutionMode.DIRECT)
            throw new IllegalStateException("Collaborative and autonomous execution are not enabled yet");
        List<AgentResult> referencedResults = resolveReferences(sessionId, owner, referencedResultIds);
        List<RunResultReference> references = referencedResults.stream()
                .map(result -> new RunResultReference(result.resultId(), result.runId(), result.bodySha256()))
                .toList();
        // 当前发布记录只验证员工仍可用；版本始终来自 Session，不能跟随发布变化。
        var published = employees.findPublished(DEFAULT_TENANT, session.employeeId())
                .orElseThrow(() -> new IllegalStateException("Session employee is no longer published"));
        if (session.definitionVersionId() == null) {
            session = store.pinDefinitionVersion(sessionId, owner, published.definition().id());
        }
        long versionId = session.definitionVersionId();
        // 能力包在发布时冻结；缺少能力包的定义属于 Harness 运行时之前的产物，
        // 必须重新发布后才能运行（规格 §8/§10.2）。
        HarnessDefinitionBundle bundle = bundles.findByDefinitionVersionId(versionId)
                .orElseThrow(() -> new IllegalStateException("Published definition has no runtime bundle; re-publish the employee first"));
        RunExecutionTarget target;
        HarnessDefinitionBundle executionBundle = bundle;
        boolean readOnlyToolsOnly = isTeamProfile(bundle.configuration().profile());
        if (targetRoleId == null || targetRoleId.isBlank()
                || RunExecutionTarget.COORDINATOR_ROLE.equals(targetRoleId)) {
            target = RunExecutionTarget.coordinator(session.employeeId(), versionId);
        } else {
            EmployeeRuntimeConfiguration configuration = bundle.configuration();
            EmployeeRuntimeConfiguration.TeamConfiguration team = configuration.team();
            if (!readOnlyToolsOnly || team == null)
                throw new IllegalArgumentException("Session does not expose selectable expert roles");
            if (!team.userSelectableRoles().contains(targetRoleId))
                throw new IllegalArgumentException("The requested role is not selectable in this Session");
            EmployeeRuntimeConfiguration.FixedMember member = configuration.members().stream()
                    .filter(candidate -> targetRoleId.equals(candidate.roleId())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Frozen selectable role has no member definition"));
            executionBundle = bundles.findByDefinitionVersionId(member.definitionVersionId())
                    .orElseThrow(() -> new IllegalStateException("Frozen member runtime bundle is missing"));
            if (executionBundle.definitionVersionId() != member.definitionVersionId())
                throw new IllegalStateException("Frozen member bundle version does not match Session role");
            String slotId = UUID.randomUUID().toString();
            SessionRoleSlot slot = store.createRoleSlot(new SessionRoleSlot(slotId, session.id(), owner,
                    targetRoleId, member.employeeId(), member.definitionVersionId(),
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), clock.instant()));
            if (!slot.roleId().equals(targetRoleId) || slot.employeeId() != member.employeeId()
                    || slot.definitionVersionId() != member.definitionVersionId())
                throw new IllegalStateException("Persisted role slot does not match the frozen Session role");
            target = new RunExecutionTarget(executionMode, targetRoleId, member.employeeId(),
                    member.definitionVersionId(), slot.id());
        }
        Instant now = clock.instant();
        RunId runId = RunId.newId();
        HarnessRunSpec runSpec = runSpecFactory.create(runId, owner, executionBundle, null, readOnlyToolsOnly);
        String requestDigest = digestRequest(input, target, references);
        return store.createRun(new AgentRun(runId, session.id(), owner, session.employeeId(), versionId, clientRequestId,
                requestDigest, RunState.QUEUED, now, null, null, bundle.configuration().profile()),
                input, runSpec, target, references);
    }

    public List<AgentResult> referenceableResults(SessionId sessionId, UserId owner) {
        get(sessionId, owner);
        if (results == null) return List.of();
        return results.listReferenceable(sessionId, owner, 50);
    }

    private List<AgentResult> resolveReferences(SessionId sessionId, UserId owner, List<String> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return List.of();
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        for (String id : requestedIds) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Referenced result id is required");
            uniqueIds.add(id);
        }
        if (uniqueIds.size() > MAX_REFERENCED_RESULTS)
            throw new IllegalArgumentException("At most " + MAX_REFERENCED_RESULTS + " results may be referenced");
        if (results == null) throw new IllegalStateException("Result references are unavailable");
        List<AgentResult> resolved = new ArrayList<>(uniqueIds.size());
        long totalBytes = 0;
        for (String id : uniqueIds) {
            AgentResult result = results.findReferenceable(id, sessionId, owner)
                    .orElseThrow(() -> new IllegalArgumentException("Referenced result is not visible in this Session"));
            if (result.legacySummary() || result.visibility() != EventVisibility.USER)
                throw new IllegalArgumentException("Only complete user-visible results may be referenced");
            totalBytes += result.byteSize();
            if (totalBytes > MAX_REFERENCED_RESULT_BYTES)
                throw new IllegalArgumentException("Referenced result content exceeds the Run input limit");
            resolved.add(result);
        }
        return List.copyOf(resolved);
    }

    private static String digestRequest(String input, RunExecutionTarget target, List<RunResultReference> references) {
        if (RunExecutionTarget.COORDINATOR_ROLE.equals(target.roleId()) && target.mode() == RunExecutionMode.DIRECT
                && references.isEmpty()) return digest(input); // Preserve pre-role idempotency for legacy/default runs.
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("input", input);
        snapshot.put("mode", target.mode().name());
        snapshot.put("roleId", target.roleId());
        snapshot.put("employeeId", target.employeeId());
        snapshot.put("definitionVersionId", target.definitionVersionId());
        snapshot.put("references", references.stream().map(reference -> Map.of(
                "resultId", reference.resultId(), "sourceRunId", reference.sourceRunId().value(),
                "bodySha256", reference.bodySha256())).toList());
        return digest(com.haizhuo.brain.kernel.json.CanonicalJson.write(snapshot));
    }

    public List<SessionRoleOption> roles(SessionId sessionId, UserId owner) {
        AgentSession session = get(sessionId, owner);
        if (session.legacyRuntime() || session.definitionVersionId() == null)
            return List.of(new SessionRoleOption(RunExecutionTarget.COORDINATOR_ROLE, "员工",
                    session.employeeId(), session.definitionVersionId(), true));
        HarnessDefinitionBundle bundle = bundles.findByDefinitionVersionId(session.definitionVersionId())
                .orElseThrow(() -> new IllegalStateException("Session runtime bundle is missing"));
        List<SessionRoleOption> options = new java.util.ArrayList<>();
        options.add(new SessionRoleOption(RunExecutionTarget.COORDINATOR_ROLE, bundle.employeeName(),
                session.employeeId(), session.definitionVersionId(), true));
        EmployeeRuntimeConfiguration configuration = bundle.configuration();
        if (!isTeamProfile(configuration.profile()) || configuration.team() == null) return List.copyOf(options);
        for (String roleId : configuration.team().userSelectableRoles()) {
            EmployeeRuntimeConfiguration.FixedMember member = configuration.members().stream()
                    .filter(candidate -> roleId.equals(candidate.roleId())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Frozen selectable role has no member definition"));
            HarnessDefinitionBundle memberBundle = bundles.findByDefinitionVersionId(member.definitionVersionId())
                    .orElseThrow(() -> new IllegalStateException("Frozen member runtime bundle is missing"));
            options.add(new SessionRoleOption(roleId, memberBundle.employeeName(), member.employeeId(),
                    member.definitionVersionId(), true));
        }
        return List.copyOf(options);
    }

    public RunExecutionTarget executionTarget(AgentRun run, UserId owner) {
        return store.findExecutionTarget(run.id(), owner)
                .orElseGet(() -> RunExecutionTarget.coordinator(run.employeeId(), run.definitionVersionId()));
    }

    private static boolean isTeamProfile(RuntimeProfile profile) {
        return profile == RuntimeProfile.TEAM_READONLY || profile == RuntimeProfile.TEAM_AUTONOMOUS_READONLY;
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
    /**
     * 会话事件分页（P2）：带出保留窗口下界，并判定请求游标是否已失效。
     * 失效必须显式告知而不是返回空批次：空批次与"确实没有新事件"在载荷上无法区分，
     * 客户端会永久停在旧快照上而不再自愈。
     */
    public SessionEventPage sessionEventPage(SessionId sessionId, UserId owner, long afterCursor, int limit) {
        get(sessionId, owner);
        return loadSessionEventPage(sessionId, owner, afterCursor, limit);
    }
    /** 归属已在本次订阅建立时校验过；语义同 {@link #sessionEventPage}。 */
    public SessionEventPage sessionEventPageOfOwnedSession(SessionId sessionId, UserId owner,
                                                           long afterCursor, int limit) {
        return loadSessionEventPage(sessionId, owner, afterCursor, limit);
    }
    private SessionEventPage loadSessionEventPage(SessionId sessionId, UserId owner, long afterCursor, int limit) {
        return store.scanSessionEventPage(sessionId,owner,Math.max(0,afterCursor),Math.max(1,Math.min(200,limit)));
    }
    private java.util.List<SessionEvent> findSessionEvents(SessionId sessionId, UserId owner, long afterCursor, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        return store.findSessionEvents(sessionId, owner, Math.max(afterCursor, 0), boundedLimit).stream()
                .filter(e->e.visibility()==EventVisibility.USER).toList();
    }
    public SessionSnapshot snapshot(SessionId sessionId, UserId owner, int limit) {
        return store.snapshot(sessionId,owner,limit);
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
