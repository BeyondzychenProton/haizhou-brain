package com.haizhuo.brain.runtime.agentscope.team;

import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamInfo;
import io.agentscope.harness.agent.team.TeamMemberInfo;
import io.agentscope.harness.agent.team.TeamMessage;
import io.agentscope.harness.agent.team.TeamTask;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Thin authorization boundary around AgentScope's native TeamClient.
 *
 * <p>The native TeamTool accepts model supplied claim owners, and its action gate treats an empty
 * allowlist as unrestricted. This wrapper binds every mutation to one trusted member and applies
 * the frozen role/action/recipient scope before delegating task state and mailbox storage to the
 * native client.
 */
public final class ScopedTeamClient implements TeamClient {

    private static final int MAX_SUBJECT_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 12_000;
    private static final int MAX_MESSAGE_LENGTH = 16_000;
    private static final int MAX_RESULT_LENGTH = 24_000;

    private final TeamClient delegate;
    private final TeamContext context;
    private final TeamActionBudget budget;
    private final Set<String> members;
    private final Set<String> actions;
    private final Set<String> recipients;
    private final Runnable completedCallback;

    public ScopedTeamClient(
            TeamClient delegate,
            TeamContext context,
            TeamActionBudget budget,
            Set<String> allowedRecipients) {
        this(delegate, context, budget, allowedRecipients, () -> { });
    }

    public ScopedTeamClient(
            TeamClient delegate,
            TeamContext context,
            TeamActionBudget budget,
            Set<String> allowedRecipients,
            Runnable completedCallback) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.context = Objects.requireNonNull(context, "context");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.completedCallback = Objects.requireNonNull(completedCallback, "completedCallback");
        if (context.teamName() == null || context.teamName().isBlank()
                || context.resolvedNamespace().isBlank()
                || context.myRole() == null || context.myRole().isBlank()) {
            throw new IllegalArgumentException("team scope identity is required");
        }
        if (context.availableActions() == null || context.availableActions().isEmpty()) {
            throw new IllegalArgumentException("team action allowlist must be non-empty");
        }
        this.actions = normalized(context.availableActions());
        if (this.actions.contains("")) {
            throw new IllegalArgumentException("team action allowlist contains a blank action");
        }
        HashSet<String> roster = new HashSet<>();
        roster.add("lead");
        roster.add(context.myRole());
        if (context.members() != null) {
            context.members().stream()
                    .map(TeamContext.MemberSnapshot::name)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .forEach(roster::add);
        }
        this.members = Set.copyOf(roster);
        this.recipients = Set.copyOf(allowedRecipients == null ? Set.of() : allowedRecipients);
        if (!this.members.containsAll(this.recipients) || this.recipients.contains(context.myRole())) {
            throw new IllegalArgumentException("team recipients must be other frozen roster members");
        }
    }

    @Override
    public Mono<List<TeamTask>> listTasks(String namespace, String teamName) {
        return read("listTasks", namespace, teamName, () -> delegate.listTasks(namespace, teamName));
    }

    @Override
    public Mono<TeamTask> createTask(
            String namespace,
            String teamName,
            String subject,
            String description,
            List<String> blockedBy,
            String owner) {
        return Mono.defer(
                () -> {
                    require("createTask", namespace, teamName);
                    requireLead();
                    String normalizedOwner = owner == null ? "" : owner.trim();
                    if (!normalizedOwner.isEmpty() && !members.contains(normalizedOwner)) {
                        return Mono.error(new SecurityException("task owner is outside the team roster"));
                    }
                    requireLength(subject, 1, MAX_SUBJECT_LENGTH, "subject");
                    requireLength(description, 0, MAX_DESCRIPTION_LENGTH, "description");
                    List<String> dependencies = blockedBy == null ? List.of() : List.copyOf(blockedBy);
                    return delegate.listTasks(namespace, teamName)
                            .flatMap(
                                    tasks -> {
                                        Set<String> ids = tasks.stream().map(TeamTask::taskId).collect(java.util.stream.Collectors.toSet());
                                        if (!ids.containsAll(dependencies)) {
                                            return Mono.error(new IllegalArgumentException("task dependency is not on this team board"));
                                        }
                                        budget.reserveTask();
                                        return delegate.createTask(
                                                namespace, teamName, subject.trim(),
                                                description == null ? "" : description,
                                                dependencies, normalizedOwner);
                                    });
                });
    }

    @Override
    public Mono<TeamTask> assignTask(
            String namespace, String teamName, String taskId, String owner, long expectedVersion) {
        return Mono.defer(
                () -> {
                    require("assignTask", namespace, teamName);
                    requireLead();
                    requireMember(owner);
                    return delegate.assignTask(namespace, teamName, taskId, owner, expectedVersion);
                });
    }

    @Override
    public Mono<TeamTask> claimTask(
            String namespace,
            String teamName,
            String taskId,
            String claimedBy,
            long expectedVersion) {
        return Mono.defer(
                () -> {
                    require("claimTask", namespace, teamName);
                    // claimedBy is intentionally ignored: AgentScope TeamTool accepts it from the model.
                    return delegate.claimTask(namespace, teamName, taskId, context.myRole(), expectedVersion);
                });
    }

    @Override
    public Mono<TeamTask> completeTask(String namespace, String teamName, String taskId, String result) {
        return Mono.defer(
                () -> {
                    require("completeTask", namespace, teamName);
                    requireLength(result, 0, MAX_RESULT_LENGTH, "result");
                    return requireOwnedInProgress(namespace, teamName, taskId)
                            .then(delegate.completeTask(namespace, teamName, taskId, result));
                });
    }

    @Override
    public Mono<TeamTask> failTask(String namespace, String teamName, String taskId, String reason) {
        return Mono.defer(
                () -> {
                    require("failTask", namespace, teamName);
                    requireLength(reason, 0, MAX_RESULT_LENGTH, "reason");
                    return requireOwnedInProgress(namespace, teamName, taskId)
                            .then(delegate.failTask(namespace, teamName, taskId, reason));
                });
    }

    @Override
    public Mono<TeamTask> unclaimTask(
            String namespace, String teamName, String taskId, long expectedVersion) {
        return Mono.defer(
                () -> {
                    require("unclaimTask", namespace, teamName);
                    return task(namespace, teamName, taskId)
                            .flatMap(
                                    existing -> {
                                        if (!context.myRole().equals(existing.owner())
                                                || !TeamTask.IN_PROGRESS.equals(existing.state())) {
                                            return Mono.error(new SecurityException("only the task owner may unclaim it"));
                                        }
                                        return delegate.unclaimTask(namespace, teamName, taskId, expectedVersion);
                                    });
                });
    }

    @Override
    public Mono<List<TeamTask>> listClaimableTasks(String namespace, String teamName, String forMember) {
        return Mono.defer(
                () -> {
                    require("claimTask", namespace, teamName);
                    return delegate.listClaimableTasks(namespace, teamName, context.myRole());
                });
    }

    @Override
    public Mono<TeamMessage> sendMessage(
            String namespace, String teamName, String from, String to, String content) {
        return Mono.defer(
                () -> {
                    require("sendMessage", namespace, teamName);
                    requireActor(from);
                    requireRecipient(to);
                    requireLength(content, 1, MAX_MESSAGE_LENGTH, "content");
                    budget.reserveMessageRecipients(1);
                    return delegate.sendMessage(namespace, teamName, context.myRole(), to, content);
                });
    }

    @Override
    public Mono<List<TeamMessage>> broadcastMessage(
            String namespace, String teamName, String from, String content) {
        return Mono.defer(
                () -> {
                    require("broadcastMessage", namespace, teamName);
                    requireActor(from);
                    long otherMembers = members.stream().filter(member -> !member.equals(context.myRole())).count();
                    if (otherMembers != recipients.size() || !recipients.containsAll(members.stream()
                            .filter(member -> !member.equals(context.myRole())).toList())) {
                        return Mono.error(new SecurityException("broadcast would reach an unauthorized recipient"));
                    }
                    requireLength(content, 1, MAX_MESSAGE_LENGTH, "content");
                    if (otherMembers == 0) return Mono.just(List.of());
                    budget.reserveMessageRecipients(Math.toIntExact(otherMembers));
                    return delegate.broadcastMessage(namespace, teamName, context.myRole(), content);
                });
    }

    @Override
    public Mono<List<TeamMessage>> listMessages(String namespace, String teamName, int limit) {
        return read("listMessages", namespace, teamName, () -> delegate.listMessages(namespace, teamName, limit));
    }

    @Override
    public Mono<List<TeamMemberInfo>> listMembers(String namespace, String teamName) {
        return read("listMembers", namespace, teamName, () -> delegate.listMembers(namespace, teamName));
    }

    @Override
    public Mono<Void> spawnMember(String namespace, String teamName, String name, String agentRef, String prompt) {
        return unsupported("spawnMember");
    }

    @Override
    public Mono<Void> shutdownMember(String namespace, String teamName, String memberName) {
        return unsupported("shutdownMember");
    }

    @Override
    public Mono<Void> submitPlan(String namespace, String teamName, String memberName, String planText) {
        return unsupported("submitPlan");
    }

    @Override
    public Mono<Void> approvePlan(String namespace, String teamName, String memberName) {
        return unsupported("approvePlan");
    }

    @Override
    public Mono<Void> rejectPlan(String namespace, String teamName, String memberName) {
        return unsupported("rejectPlan");
    }

    @Override
    public Mono<TeamInfo> createTeam(TeamCreateSpec spec) {
        return unsupported("createTeam");
    }

    @Override
    public Mono<Void> completeTeam(String namespace, String teamName) {
        return Mono.defer(
                () -> {
                    require("completeTeam", namespace, teamName);
                    requireLead();
                    return delegate.listTasks(namespace, teamName)
                            .flatMap(
                                    tasks -> tasks.stream().anyMatch(task -> !TeamTask.isTerminal(task.state()))
                                            ? Mono.error(new IllegalStateException("team has unfinished tasks"))
                                            : delegate.completeTeam(namespace, teamName)
                                                    .doOnSuccess(ignored -> completedCallback.run()));
                });
    }

    private <T> Mono<T> read(String action, String namespace, String teamName, SupplierMono<T> operation) {
        return Mono.defer(
                () -> {
                    require(action, namespace, teamName);
                    return operation.get();
                });
    }

    private Mono<TeamTask> task(String namespace, String teamName, String taskId) {
        return delegate.listTasks(namespace, teamName)
                .flatMap(
                        tasks -> tasks.stream()
                                .filter(candidate -> candidate.taskId().equals(taskId))
                                .findFirst()
                                .<Mono<TeamTask>>map(Mono::just)
                                .orElseGet(() -> Mono.error(new IllegalArgumentException("task not found"))));
    }

    private Mono<Void> requireOwnedInProgress(String namespace, String teamName, String taskId) {
        return task(namespace, teamName, taskId)
                .flatMap(
                        existing -> {
                            if (!context.myRole().equals(existing.owner())
                                    || !TeamTask.IN_PROGRESS.equals(existing.state())) {
                                return Mono.error(
                                        new SecurityException(
                                                "only the current task owner may settle an in-progress task"));
                            }
                            return Mono.empty();
                        });
    }

    private void require(String action, String namespace, String teamName) {
        if (!context.resolvedNamespace().equals(namespace) || !context.teamName().equals(teamName)) {
            throw new SecurityException("team operation is outside this execution scope");
        }
        String normalized = normalize(action);
        boolean allowed = actions.contains(normalized);
        if ((normalized.equals("listclaimabletasks") || normalized.equals("unclaimtask"))
                && actions.contains("claimtask")) allowed = true;
        if (normalized.equals("listmessages")
                && (actions.contains("sendmessage") || actions.contains("broadcastmessage"))) allowed = true;
        if (!allowed) throw new SecurityException("team action is not allowed for this member");
        if (normalized.equals("createtask") || normalized.equals("assigntask")
                || normalized.equals("completeteam")) requireLead();
    }

    private void requireLead() {
        if (!context.isLead() || !"lead".equals(context.myRole())) {
            throw new SecurityException("team control action requires the trusted lead identity");
        }
    }

    private void requireActor(String suppliedActor) {
        if (!context.myRole().equals(suppliedActor)) {
            throw new SecurityException("message sender does not match the trusted member identity");
        }
    }

    private void requireMember(String member) {
        if (member == null || !members.contains(member)) {
            throw new SecurityException("member is outside the frozen team roster");
        }
    }

    private void requireRecipient(String recipient) {
        requireMember(recipient);
        if (context.myRole().equals(recipient) || !recipients.contains(recipient)) {
            throw new SecurityException("message recipient is outside the approved peer scope");
        }
    }

    private static void requireLength(String value, int min, int max, String field) {
        int length = value == null ? 0 : value.length();
        if (length < min || length > max) {
            throw new IllegalArgumentException(field + " length is outside the allowed range");
        }
    }

    private static Set<String> normalized(List<String> items) {
        HashSet<String> normalized = new HashSet<>();
        items.stream().map(ScopedTeamClient::normalize).forEach(normalized::add);
        return Set.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static <T> Mono<T> unsupported(String action) {
        return Mono.error(new UnsupportedOperationException(action + " is disabled for fixed-roster teams"));
    }

    @FunctionalInterface
    private interface SupplierMono<T> {
        Mono<T> get();
    }
}
