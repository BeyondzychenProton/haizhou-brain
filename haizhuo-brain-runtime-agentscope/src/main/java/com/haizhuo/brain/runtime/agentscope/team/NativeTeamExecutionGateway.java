package com.haizhuo.brain.runtime.agentscope.team;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.agentscope.context.HaizhuoNamespaceFactory;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeEmployeeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.runtime.api.team.TeamExecutionMember;
import com.haizhuo.brain.runtime.api.team.TeamExecutionPersistence;
import com.haizhuo.brain.runtime.api.team.TeamExecutionSnapshot;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.gateway.WakeupDispatcher;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamTask;
import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Run-scoped adapter around AgentScope Java 2.0.3's native TeamClient, TeamsMiddleware,
 * WorkspaceMessageBus and WakeupDispatcher. It owns identity mapping and limits, not task scheduling.
 */
public final class NativeTeamExecutionGateway {
    public static final int MAX_WORKERS = 2;
    public static final int MAX_TASKS = PersistentTeamActionBudget.MAX_TASKS;
    public static final int MAX_ROUNDS = 12;
    public static final int MAX_STEPS_PER_ROUND = 4;
    public static final Duration MAX_DURATION = Duration.ofSeconds(180);
    private static final Duration BUS_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_RESULT_CHARS = 192_000;

    private final BaseStore baseStore;
    private final TeamExecutionPersistence persistence;
    private final NativeTeamMemberFactory memberFactory;
    private final RunControlInbox controlInbox;

    public NativeTeamExecutionGateway(BaseStore baseStore, TeamExecutionPersistence persistence,
                                      NativeTeamMemberFactory memberFactory, RunControlInbox controlInbox) {
        this.baseStore = Objects.requireNonNull(baseStore);
        this.persistence = Objects.requireNonNull(persistence);
        this.memberFactory = Objects.requireNonNull(memberFactory);
        this.controlInbox = Objects.requireNonNull(controlInbox);
    }

    /** Returns only task-board results; internal mailbox content and reasoning are never returned. */
    public String execute(RuntimeDefinitionSnapshot owner, String objective, RuntimeContext rootContext) {
        if (owner == null || owner.configuration().profile() != RuntimeProfile.TEAM_AUTONOMOUS_READONLY)
            throw new SecurityException("native Team execution is not enabled for this frozen profile");
        if (!StringUtils.hasText(objective) || objective.length() > 4_000)
            throw new IllegalArgumentException("Team objective must contain 1 to 4000 characters");
        TrustedCall trusted = trustedCall(rootContext);
        List<RuntimeEmployeeConfiguration.FixedMember> workers = validateRoster(owner);
        String executionId = UUID.randomUUID().toString();
        String teamName = "hb-" + executionId.replace("-", "") + "-g1";
        String namespace = "haizhuo-team-" + executionId.replace("-", "");
        String teamWorkspaceKey = "teamws_" + executionId.replace("-", "");
        List<TeamExecutionMember> bindings = new ArrayList<>();
        bindings.add(new TeamExecutionMember("lead", 0, owner.definitionVersionId(),
                memberSession(executionId, "lead"), TeamExecutionMember.Kind.LEAD));
        for (RuntimeEmployeeConfiguration.FixedMember worker : workers) {
            bindings.add(new TeamExecutionMember(worker.roleId(), worker.employeeId(), worker.definitionVersionId(),
                    memberSession(executionId, worker.roleId()), TeamExecutionMember.Kind.WORKER));
        }
        TeamExecutionSnapshot execution = new TeamExecutionSnapshot(executionId, trusted.runId(), trusted.userId(),
                trusted.sessionId(), trusted.call().attemptId(), trusted.call().fenceToken(), teamName, namespace,
                owner.definitionVersionId(), bindings, Instant.now());

        // Persist the Run/fence mapping before the native task board or mailbox can be changed.
        persistence.start(execution);
        TeamRun run = new TeamRun(owner, objective, trusted, execution, teamWorkspaceKey, workers);
        try {
            return run.startAndAwait();
        } catch (Throwable failure) {
            run.stopAll();
            persistence.requireRecovery(execution, reasonCode(failure));
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("native Team execution requires recovery", failure);
        } finally {
            run.close();
        }
    }

    private static List<RuntimeEmployeeConfiguration.FixedMember> validateRoster(RuntimeDefinitionSnapshot owner) {
        RuntimeEmployeeConfiguration configuration = owner.configuration();
        if (configuration.team() == null) throw new IllegalStateException("frozen Team policy is missing");
        List<RuntimeEmployeeConfiguration.FixedMember> workers = configuration.members();
        if (workers.isEmpty() || workers.size() > MAX_WORKERS)
            throw new IllegalStateException("autonomous Team requires one or two frozen workers");
        Set<String> roles = new java.util.HashSet<>();
        for (RuntimeEmployeeConfiguration.FixedMember worker : workers) {
            if (!roles.add(worker.roleId()) || worker.definition() == null
                    || worker.definition().definitionVersionId() != worker.definitionVersionId())
                throw new IllegalStateException("frozen Team worker definition is incomplete or inconsistent");
            RuntimeEmployeeConfiguration workerConfig = worker.definition().configuration();
            if (!workerConfig.members().isEmpty()
                    || workerConfig.profile() == RuntimeProfile.TEAM_AUTONOMOUS_READONLY)
                throw new IllegalStateException("nested Team definitions are not supported");
        }
        return workers;
    }

    private static TrustedCall trustedCall(RuntimeContext context) {
        if (context == null || !StringUtils.hasText(context.getUserId())
                || !StringUtils.hasText(context.getSessionId()))
            throw new SecurityException("trusted Run identity is required");
        HarnessCallContext call = context.get(HarnessCallContext.class);
        if (call == null || !"coordinator".equals(call.roleId()) || call.fenceToken() <= 0)
            throw new SecurityException("autonomous Team requires the frozen root Run scope");
        try {
            long userId = Long.parseLong(context.getUserId());
            if (userId <= 0) throw new NumberFormatException("non-positive user");
            return new TrustedCall(call.runId(), new UserId(userId), new SessionId(context.getSessionId()), call);
        } catch (NumberFormatException malformed) {
            throw new SecurityException("trusted platform user identity is invalid");
        }
    }

    private static String memberSession(String executionId, String roleId) {
        return "team_" + executionId.replace("-", "") + "_" + roleId;
    }

    private static String reasonCode(Throwable failure) {
        if (failure instanceof java.util.concurrent.TimeoutException) return "TEAM_TIMEOUT";
        if (failure instanceof SecurityException) return "TEAM_SCOPE_REJECTED";
        if (failure instanceof RejectedExecutionException) return "TEAM_WAKEUP_OVERFLOW";
        return "TEAM_OUTCOME_UNKNOWN";
    }

    private final class TeamRun implements AutoCloseable {
        private final RuntimeDefinitionSnapshot owner;
        private final String objective;
        private final TrustedCall trusted;
        private final TeamExecutionSnapshot execution;
        private final String teamWorkspaceKey;
        private final List<RuntimeEmployeeConfiguration.FixedMember> workers;
        private final Instant deadline = Instant.now().plus(MAX_DURATION);
        private final AtomicInteger rounds = new AtomicInteger();
        private final AtomicBoolean leadCompleted = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Object signal = new Object();
        private final Semaphore workerPermits = new Semaphore(MAX_WORKERS, true);
        private final ExecutorService executor;
        private final Map<String, MemberRuntime> sessions = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, MemberRuntime> sessionsBySessionId = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, Disposable> subscriptions = new java.util.concurrent.ConcurrentHashMap<>();
        private final LocalTeamClient nativeClient = new LocalTeamClient(baseStore);
        private MessageBus bus;
        private WakeupDispatcher dispatcher;
        private Future<?> firstLeadTurn;

        private TeamRun(RuntimeDefinitionSnapshot owner, String objective, TrustedCall trusted,
                        TeamExecutionSnapshot execution, String teamWorkspaceKey,
                        List<RuntimeEmployeeConfiguration.FixedMember> workers) {
            this.owner = owner;
            this.objective = objective;
            this.trusted = trusted;
            this.execution = execution;
            this.teamWorkspaceKey = teamWorkspaceKey;
            this.workers = workers;
            this.executor = Executors.newFixedThreadPool(MAX_WORKERS + 1, runnable -> {
                Thread thread = new Thread(runnable, "haizhuo-team-" + execution.teamExecutionId());
                thread.setDaemon(true);
                return thread;
            });
        }

        private String startAndAwait() throws Exception {
            prepareNativeTeam();
            dispatcher.start();
            firstLeadTurn = executor.submit(() -> {
                try {
                    executeMemberTurn(sessions.get("lead"),
                            "请按只读目标组织固定成员，使用任务板完成工作，并在所有任务进入终态后调用 completeTeam。");
                } catch (Throwable failure) {
                    fail(failure);
                    if (failure instanceof Error error) throw error;
                    throw new IllegalStateException("Team lead turn failed", failure);
                }
            });
            awaitCompletion();
            List<TeamTask> tasks = nativeClient.listTasks(execution.namespace(), execution.teamName())
                    .block(BUS_TIMEOUT);
            if (tasks == null || tasks.size() > MAX_TASKS || tasks.stream().anyMatch(task -> !TeamTask.isTerminal(task.state())))
                throw new IllegalStateException("native Team board is incomplete at the completion barrier");
            awaitIdleAndDrained();
            String result = safeResult(tasks);
            if (!persistence.complete(execution, CanonicalJson.sha256Hex(result.getBytes(StandardCharsets.UTF_8))))
                throw new IllegalStateException("Team completion lost the active Run fence");
            return result;
        }

        private void prepareNativeTeam() {
            AbstractFilesystem busDelegate = new RemoteFilesystemSpec(baseStore)
                    .isolationScope(IsolationScope.SESSION)
                    .addSharedPrefix("team-bus/")
                    .toFilesystem(Path.of(System.getProperty("java.io.tmpdir"), "haizhuo-team-bus"),
                            "team-" + execution.teamExecutionId(), new HaizhuoNamespaceFactory());
            HarnessCallContext busCall = new HarnessCallContext(trusted.call().runId(), trusted.call().attemptId(),
                    trusted.call().fenceToken(), teamWorkspaceKey, trusted.call().constraints(), null, "lead");
            RuntimeContext busContext = RuntimeContext.builder().userId(Long.toString(trusted.userId().value()))
                    .sessionId("team-bus-" + execution.teamExecutionId())
                    .put(HarnessCallContext.class, busCall).build();
            String busRoot = "/team-bus/" + execution.teamExecutionId();
            bus = new WorkspaceMessageBus(new TeamBusFilesystem(busDelegate, busContext, busRoot), busRoot);

            List<TeamMemberSpec> memberSpecs = workers.stream()
                    .map(worker -> new TeamMemberSpec(worker.roleId(), "version:" + worker.definitionVersionId(),
                            "只读 Team 固定成员", "byo"))
                    .toList();
            nativeClient.createTeam(new TeamCreateSpec(execution.teamName(), execution.namespace(), objective,
                    "version:" + owner.definitionVersionId(), "只读 Team lead", memberSpecs)).block(BUS_TIMEOUT);

            List<TeamContext.MemberSnapshot> roster = new ArrayList<>();
            roster.add(new TeamContext.MemberSnapshot("lead", "version:" + owner.definitionVersionId(), "idle"));
            workers.forEach(worker -> roster.add(new TeamContext.MemberSnapshot(worker.roleId(),
                    "version:" + worker.definitionVersionId(), "idle")));
            TeamExecutionMember leadBinding = execution.members().get(0);
            TeamContext leadContext = new TeamContext(execution.teamName(), execution.namespace(), objective,
                    "lead", true, roster,
                    List.of("listTasks", "createTask", "assignTask", "sendMessage", "broadcastMessage",
                            "listMessages", "listMembers", "completeTeam"));
            ScopedTeamClient leadClient = new ScopedTeamClient(nativeClient, leadContext,
                    new PersistentTeamActionBudget(persistence, execution),
                    workers.stream().map(RuntimeEmployeeConfiguration.FixedMember::roleId)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    () -> {
                        leadCompleted.set(true);
                        signalAll();
                    });
            RuntimeContext leadRuntime = memberFactory.runtimeContext(Long.toString(trusted.userId().value()),
                    leadBinding.harnessSessionKey(), trusted.call(), "lead", teamWorkspaceKey);
            HarnessAgent leadAgent = memberFactory.build(owner, "lead", leadBinding.harnessSessionKey(),
                    leadClient, leadContext, bus);
            MemberRuntime leadMember = new MemberRuntime("lead", leadBinding.harnessSessionKey(), leadAgent,
                    leadRuntime, true);
            sessions.put("lead", leadMember);
            sessionsBySessionId.put(leadMember.sessionId, leadMember);

            for (int i = 0; i < workers.size(); i++) {
                RuntimeEmployeeConfiguration.FixedMember worker = workers.get(i);
                TeamExecutionMember binding = execution.members().get(i + 1);
                TeamContext workerContext = new TeamContext(execution.teamName(), execution.namespace(), objective,
                        worker.roleId(), false, roster,
                        List.of("listTasks", "claimTask", "completeTask", "failTask", "sendMessage",
                                "broadcastMessage", "listMessages", "listMembers"));
                ScopedTeamClient workerClient = new ScopedTeamClient(nativeClient, workerContext,
                        new PersistentTeamActionBudget(persistence, execution), Set.of("lead"));
                RuntimeContext workerRuntime = memberFactory.runtimeContext(Long.toString(trusted.userId().value()),
                        binding.harnessSessionKey(), trusted.call(), worker.roleId(), teamWorkspaceKey);
                HarnessAgent workerAgent = memberFactory.build(worker.definition(), worker.roleId(),
                        binding.harnessSessionKey(), workerClient, workerContext, bus);
                MemberRuntime workerMember = new MemberRuntime(worker.roleId(), binding.harnessSessionKey(),
                        workerAgent, workerRuntime, false);
                sessions.put(worker.roleId(), workerMember);
                sessionsBySessionId.put(workerMember.sessionId, workerMember);
            }

            dispatcher = new WakeupDispatcher(bus, new WakeupDispatcher.WakeupTarget() {
                @Override
                public boolean isSessionRunning(String sessionId) {
                    MemberRuntime member = sessionsBySessionId.get(sessionId);
                    return member != null && member.active.get();
                }

                @Override
                @Deprecated
                public Mono<Msg> runWakeup(String sessionId) {
                    return runWakeup("", sessionId);
                }

                @Override
                public Mono<Msg> runWakeup(String userId, String sessionId) {
                    MemberRuntime member = sessionsBySessionId.get(sessionId);
                    if (member == null) return Mono.empty();
                    if (StringUtils.hasText(userId) && !Long.toString(trusted.userId().value()).equals(userId))
                        return Mono.<Msg>error(new SecurityException("Team wakeup user is outside its frozen Run"))
                                .doOnError(TeamRun.this::fail);
                    return Mono.fromCallable(() -> executeMemberTurn(member,
                                    "Team 有新的任务或消息，请读取任务板和收件箱后继续。"))
                            .subscribeOn(Schedulers.fromExecutor(executor))
                            .then(Mono.<Msg>empty())
                            .doOnError(TeamRun.this::fail);
                }
            });
        }

        private void awaitCompletion() throws Exception {
            while (!leadCompleted.get()) {
                checkAbort();
                if (Instant.now().isAfter(deadline)) throw new java.util.concurrent.TimeoutException("Team deadline exceeded");
                synchronized (signal) {
                    TimeUnit.MILLISECONDS.timedWait(signal, 100);
                }
            }
            awaitIdleAndDrained();
        }

        private void awaitIdleAndDrained() throws Exception {
            while (true) {
                checkAbort();
                if (Instant.now().isAfter(deadline)) throw new java.util.concurrent.TimeoutException("Team deadline exceeded");
                boolean active = sessions.values().stream().anyMatch(member -> member.active.get());
                boolean pending = false;
                for (MemberRuntime member : sessions.values()) {
                    Boolean inbox = bus.inboxHasMessages(member.sessionId).block(BUS_TIMEOUT);
                    if (Boolean.TRUE.equals(inbox) || member.pending.get()) pending = true;
                }
                if (!active && !pending) return;
                synchronized (signal) {
                    TimeUnit.MILLISECONDS.timedWait(signal, 50);
                }
            }
        }

        private Msg executeMemberTurn(MemberRuntime member, String firstPrompt) throws Exception {
            if (member == null) return null;
            boolean workerPermit = false;
            if (!member.lead) {
                long remaining = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
                workerPermit = workerPermits.tryAcquire(remaining, TimeUnit.MILLISECONDS);
                if (!workerPermit) throw new java.util.concurrent.TimeoutException("Team worker concurrency wait expired");
            }
            try {
                long remaining = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
                if (!member.turnLock.tryLock(remaining, TimeUnit.MILLISECONDS)) {
                    member.pending.set(true);
                    return null;
                }
                long activeOrdinal = 0;
                boolean lifecycleStarted = false;
                try {
                    String prompt = firstPrompt;
                    Msg lastResult = null;
                    do {
                        checkAbort();
                        int round = rounds.incrementAndGet();
                        if (round > MAX_ROUNDS) throw new IllegalStateException("Team round limit exceeded");
                        member.pending.set(false);
                        member.active.set(true);
                        long ordinal = member.turnOrdinal.incrementAndGet();
                        activeOrdinal = ordinal;
                        Instant started = Instant.now();
                        if (!persistence.recordMemberEvent(execution, member.roleId,
                                TeamExecutionPersistence.MemberEvent.STARTED, ordinal, started))
                            throw new SecurityException("Team member start lost the Run fence");
                        lifecycleStarted = true;
                        CountDownLatch done = new CountDownLatch(1);
                        AtomicReference<Throwable> turnFailure = new AtomicReference<>();
                        AtomicReference<Msg> result = new AtomicReference<>();
                        Disposable subscription = member.agent.streamEvents(prompt, member.context)
                                .doFinally(ignored -> done.countDown())
                                .subscribe(event -> {
                                    if (event instanceof AgentResultEvent completed) result.set(completed.getResult());
                                }, error -> {
                                    turnFailure.set(error);
                                    done.countDown();
                                });
                        subscriptions.put(member.sessionId, subscription);
                        remaining = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
                        if (!done.await(remaining, TimeUnit.MILLISECONDS)) {
                            subscription.dispose();
                            throw new java.util.concurrent.TimeoutException("Team member turn timed out");
                        }
                        subscriptions.remove(member.sessionId, subscription);
                        if (turnFailure.get() != null) throw new IllegalStateException("Team member turn failed", turnFailure.get());
                        lastResult = result.get();
                        if (!persistence.recordMemberEvent(execution, member.roleId,
                                TeamExecutionPersistence.MemberEvent.ENDED, ordinal, Instant.now()))
                            throw new SecurityException("Team member result lost the Run fence");
                        lifecycleStarted = false;
                        member.active.set(false);
                        signalAll();
                        Boolean hasInbox = bus.inboxHasMessages(member.sessionId).block(BUS_TIMEOUT);
                        boolean mustDrain = member.pending.getAndSet(false) || Boolean.TRUE.equals(hasInbox);
                        if (!mustDrain) break;
                        prompt = "请处理尚未读取的 Team 通知，并检查当前任务板。";
                    } while (true);
                    return lastResult;
                } catch (Throwable turnProblem) {
                    fail(turnProblem);
                    if (lifecycleStarted) {
                        try {
                            persistence.recordMemberEvent(execution, member.roleId,
                                    TeamExecutionPersistence.MemberEvent.STOPPED, activeOrdinal, Instant.now());
                        } catch (Throwable stopEventFailure) {
                            turnProblem.addSuppressed(stopEventFailure);
                        }
                    }
                    if (turnProblem instanceof Exception exception) throw exception;
                    if (turnProblem instanceof Error error) throw error;
                    throw new IllegalStateException("Team member turn failed", turnProblem);
                } finally {
                    member.active.set(false);
                    member.turnLock.unlock();
                    if (member.pending.getAndSet(false)) {
                        bus.enqueueWakeup(Long.toString(trusted.userId().value()), member.sessionId,
                                "team-" + execution.teamExecutionId()).block(BUS_TIMEOUT);
                    }
                    signalAll();
                }
            } finally {
                if (workerPermit) workerPermits.release();
            }
        }

        private void checkAbort() {
            Throwable problem = failure.get();
            if (problem != null) throw new IllegalStateException("another Team member failed", problem);
            if (controlInbox.isCancellationRequested(trusted.runId()))
                throw new java.util.concurrent.CancellationException("parent Run was cancelled");
        }

        private void stopAll() {
            if (dispatcher != null) dispatcher.close();
            subscriptions.values().forEach(Disposable::dispose);
            synchronized (signal) { signal.notifyAll(); }
            executor.shutdownNow();
            try { executor.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }

        private String safeResult(List<TeamTask> tasks) {
            List<Map<String, Object>> safeTasks = new ArrayList<>();
            for (TeamTask task : tasks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", task.taskId());
                item.put("subject", task.subject());
                item.put("state", task.state());
                item.put("owner", task.owner());
                item.put("result", task.result());
                safeTasks.add(item);
            }
            String serialized = CanonicalJson.write(Map.of("teamExecutionId", execution.teamExecutionId(),
                    "tasks", safeTasks));
            if (serialized.length() > MAX_RESULT_CHARS)
                throw new IllegalStateException("Team task results exceed the parent tool response limit");
            return serialized;
        }

        private void signalAll() {
            synchronized (signal) { signal.notifyAll(); }
        }

        @Override
        public void close() {
            if (dispatcher != null) dispatcher.close();
            subscriptions.values().forEach(Disposable::dispose);
            sessions.values().forEach(member -> {
                member.agent.close();
                TeamsMiddleware.unregisterSession(member.sessionId);
            });
            if (bus != null) bus.close();
            executor.shutdownNow();
            if (firstLeadTurn != null && !firstLeadTurn.isDone()) firstLeadTurn.cancel(true);
        }

        private final class MemberRuntime {
            private final String roleId;
            private final String sessionId;
            private final HarnessAgent agent;
            private final RuntimeContext context;
            private final boolean lead;
            private final AtomicBoolean active = new AtomicBoolean();
            private final AtomicBoolean pending = new AtomicBoolean();
            private final AtomicInteger turnOrdinal = new AtomicInteger();
            private final ReentrantLock turnLock = new ReentrantLock();

            private MemberRuntime(String roleId, String sessionId, HarnessAgent agent, RuntimeContext context,
                                  boolean lead) {
                this.roleId = roleId;
                this.sessionId = sessionId;
                this.agent = agent;
                this.context = context;
                this.lead = lead;
            }

        }

        private void fail(Throwable error) {
            failure.compareAndSet(null, error);
            signalAll();
        }
    }

    private record TrustedCall(RunId runId, UserId userId, SessionId sessionId, HarnessCallContext call) { }

}
