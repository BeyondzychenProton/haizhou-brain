package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** 负责协作进度的属主校验、安全状态映射、稳定游标和内容版本标记。 */
public final class RunProgressQueryService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_RESULT_BYTES = 1024 * 1024;
    private static final Pattern WORK_ITEM_REF = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final List<String> PUBLIC_RESULT_KINDS = List.of("DELEGATION_FINAL", "TEAM_RESULT");
    private static final Set<String> SAFE_TEAM_RECOVERY_REASONS = Set.of(
            "TEAM_TIMEOUT", "TEAM_SCOPE_REJECTED", "TEAM_WAKEUP_OVERFLOW", "TEAM_OUTCOME_UNKNOWN");

    private final SessionApplicationService sessions;
    private final RunProgressQueryStore store;

    public RunProgressQueryService(SessionApplicationService sessions, RunProgressQueryStore store) {
        this.sessions = Objects.requireNonNull(sessions);
        this.store = Objects.requireNonNull(store);
    }

    public ProgressResult progress(UserId owner, String rawRunId, String ifVersion) {
        RunId runId = ownedRun(owner, rawRunId);
        RunProgressQueryStore.SnapshotFacts facts;
        try {
            facts = store.snapshot(owner, runId);
        } catch (RunProgressQueryStore.RunNotFoundException missing) {
            throw new RunProgressNotFoundException();
        }
        List<RunProgressProjection.WorkItemSummary> allItems = facts.workItems().stream()
                .map(RunProgressQueryService::summary).toList();
        List<RunProgressProjection.WorkItemSummary> items = allItems.stream().limit(DEFAULT_LIMIT).toList();
        boolean hasMore = facts.workItems().size() > DEFAULT_LIMIT;
        RunProgressProjection.Summary summary = summaryCounts(facts.workItems());
        boolean budgetAvailable = facts.invocationCount() > 0;
        RunProgressProjection.DelegationBudget budget = !budgetAvailable ? null
                : new RunProgressProjection.DelegationBudget(facts.invocationCount(), facts.invocationLimit(),
                facts.activeInvocationCount(), facts.parallelLimit());
        RunProgressProjection.Team team = facts.team() == null ? null : team(facts.team());
        boolean available = !facts.workItems().isEmpty() || facts.invocationCount() > 0 || team != null;
        String version = version(runId.value(), facts, summary, budget, allItems, hasMore, team, available);
        RunProgressProjection projection = new RunProgressProjection(1, runId.value(), facts.runtimeProfile(),
                facts.runState(), version, facts.asOfSessionCursor(), facts.updatedAt(), available, summary,
                budget, items, hasMore, team);
        return new ProgressResult(projection, version.equals(cleanVersion(ifVersion)));
    }

    public RunWorkItemPage workItems(UserId owner, String rawRunId, String cursor, Integer requestedLimit) {
        RunId runId = ownedRun(owner, rawRunId);
        int limit = limit(requestedLimit);
        String filter = CanonicalJson.sha256(Map.of("runId", runId.value(), "owner", owner.value()));
        RunProgressQueryStore.Position before = decode(cursor, owner, runId, filter);
        List<RunProgressQueryStore.WorkItemFact> rows = store.workItems(owner, runId, before, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<RunProgressQueryStore.WorkItemFact> selected = rows.subList(0, Math.min(limit, rows.size()));
        String next = hasMore && !selected.isEmpty()
                ? encode(owner, runId, filter, selected.get(selected.size() - 1)) : null;
        return new RunWorkItemPage(selected.stream().map(RunProgressQueryService::summary).toList(), next, hasMore);
    }

    public Optional<RunWorkItemDetail> workItem(UserId owner, String rawRunId, String workItemRef) {
        RunId runId = ownedRun(owner, rawRunId);
        if (workItemRef == null || !WORK_ITEM_REF.matcher(workItemRef).matches())
            throw new IllegalArgumentException("workItemRef is invalid");
        return store.workItem(owner, runId, workItemRef).map(facts -> new RunWorkItemDetail(runId.value(),
                summary(facts.item()), facts.revisions().stream().map(RunProgressQueryService::revision).toList()));
    }

    public Optional<RunWorkItemResult> workItemResult(UserId owner, String rawRunId, String workItemRef,
                                                       int assignmentRevision) {
        RunId runId = ownedRun(owner, rawRunId);
        if (workItemRef == null || !WORK_ITEM_REF.matcher(workItemRef).matches())
            throw new IllegalArgumentException("workItemRef is invalid");
        if (assignmentRevision < 1) throw new IllegalArgumentException("assignmentRevision is invalid");
        return store.workItemResult(owner, runId, workItemRef, assignmentRevision)
                .filter(RunProgressQueryService::validUserResult)
                .map(result -> new RunWorkItemResult(runId.value(), workItemRef, assignmentRevision,
                        result.mediaType(), result.body(), result.bodySha256(), result.byteSize(), result.createdAt()));
    }

    private RunId ownedRun(UserId owner, String rawRunId) {
        Objects.requireNonNull(owner, "owner");
        if (rawRunId == null || rawRunId.isBlank() || rawRunId.length() > 64)
            throw new RunProgressNotFoundException();
        RunId runId;
        try { runId = new RunId(rawRunId); }
        catch (RuntimeException invalid) { throw new RunProgressNotFoundException(); }
        try { sessions.getRun(runId, owner); }
        catch (IllegalArgumentException notOwnedOrMissing) { throw new RunProgressNotFoundException(); }
        return runId;
    }

    private static RunProgressProjection.Summary summaryCounts(List<RunProgressQueryStore.WorkItemFact> items) {
        int active = 0, returned = 0, accepted = 0, revisionRequested = 0, rejected = 0, unknown = 0;
        for (var item : items) {
            String state = workItemState(item);
            if ("PENDING".equals(state) || "RUNNING".equals(state)) active++;
            if (item.resultId() != null) returned++;
            switch (state) {
                case "ACCEPTED" -> accepted++;
                case "REVISION_REQUESTED" -> revisionRequested++;
                case "REJECTED" -> rejected++;
                case "UNKNOWN" -> unknown++;
                default -> { }
            }
        }
        return new RunProgressProjection.Summary(items.size(), active, returned, accepted,
                revisionRequested, rejected, unknown);
    }

    private static RunProgressProjection.WorkItemSummary summary(RunProgressQueryStore.WorkItemFact fact) {
        String sourceKind = sourceKind(fact.sourceKind());
        String role = switch (sourceKind) {
            case "RUNTIME_GENERATED" -> "readonly_analyst";
            case "BUILTIN_GENERAL_PURPOSE" -> "general_purpose";
            default -> safeRoleId(fact.roleId());
        };
        String executor = switch (sourceKind) {
            case "RUNTIME_GENERATED" -> "只读分析";
            case "BUILTIN_GENERAL_PURPOSE" -> "通用只读";
            default -> safeDisplayName(fact.executorDisplayName());
        };
        String label = "分析任务 " + Math.max(1, fact.displayOrdinal());
        RunProgressProjection.RevisionSummary latest = new RunProgressProjection.RevisionSummary(
                fact.assignmentRevision(), executionState(fact), status(fact.formatStatus(),
                List.of("PENDING", "VALID", "INVALID", "UNKNOWN")), reviewStatus(fact.reviewStatus()),
                resultAvailability(fact), userVisibleResultId(fact), fact.revisionCreatedAt(), fact.completedAt());
        return new RunProgressProjection.WorkItemSummary(fact.workItemRef(), label, role, executor,
                sourceKind, fact.required(), fact.assignmentRevision(), workItemState(fact), fact.createdAt(),
                fact.updatedAt(), null, latest);
    }

    private static RunProgressProjection.RevisionSummary revision(RunProgressQueryStore.RevisionFact fact) {
        return new RunProgressProjection.RevisionSummary(fact.assignmentRevision(),
                executionState(fact.invocationState(), fact.resultId()),
                status(fact.formatStatus(), List.of("PENDING", "VALID", "INVALID", "UNKNOWN")),
                reviewStatus(fact.reviewStatus()), resultAvailability(fact.resultId(), fact.resultKind(),
                        fact.resultVisibility(), fact.resultBodyAvailable()), userVisibleResultId(fact.resultId(),
                        fact.resultKind(), fact.resultVisibility(), fact.resultBodyAvailable()),
                fact.createdAt(), fact.completedAt());
    }

    private static String workItemState(RunProgressQueryStore.WorkItemFact item) {
        String review = reviewStatus(item.reviewStatus());
        if ("ACCEPTED".equals(review) || "REVISION_REQUESTED".equals(review) || "REJECTED".equals(review)) return review;
        if ("INVALID".equals(item.formatStatus())) return "INVALID";
        if (item.resultId() != null) return "RETURNED";
        return switch (item.invocationState() == null ? "" : item.invocationState()) {
            case "ACCEPTED" -> "PENDING";
            case "ACTIVE" -> "RUNNING";
            case "FINISHED" -> item.resultId() == null ? "UNKNOWN" : "RETURNED";
            case "NOT_STARTED" -> "NOT_STARTED";
            default -> "UNKNOWN";
        };
    }

    private static String executionState(RunProgressQueryStore.WorkItemFact item) {
        return executionState(item.invocationState(), item.resultId());
    }

    private static String executionState(String invocationState, String resultId) {
        if (invocationState == null) return resultId == null ? "UNKNOWN" : "FINISHED";
        return switch (invocationState) {
            case "ACCEPTED" -> "ACCEPTED";
            case "ACTIVE" -> "ACTIVE";
            case "FINISHED" -> "FINISHED";
            case "NOT_STARTED" -> "NOT_STARTED";
            default -> "UNKNOWN";
        };
    }

    private static String reviewStatus(String value) {
        return status(value, List.of("PENDING", "ACCEPTED", "REVISION_REQUESTED", "REJECTED", "UNKNOWN"));
    }

    private static String status(String value, List<String> allowed) {
        if (value == null) return "UNKNOWN";
        String normalized = value.toUpperCase(java.util.Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "UNKNOWN";
    }

    private static String resultAvailability(RunProgressQueryStore.WorkItemFact item) {
        return resultAvailability(item.resultId(), item.resultKind(), item.resultVisibility(),
                item.resultBodyAvailable());
    }

    private static String resultAvailability(String resultId, String resultKind, String visibility,
                                             boolean resultBodyAvailable) {
        if (resultId == null) return "UNAVAILABLE";
        if (resultKind == null || visibility == null) return "UNAVAILABLE";
        if ("INTERNAL".equals(visibility)) return "PRIVATE";
        return "USER".equals(visibility) && PUBLIC_RESULT_KINDS.contains(resultKind) && resultBodyAvailable
                ? "USER_VISIBLE" : "UNAVAILABLE";
    }

    private static String userVisibleResultId(RunProgressQueryStore.WorkItemFact item) {
        return userVisibleResultId(item.resultId(), item.resultKind(), item.resultVisibility(),
                item.resultBodyAvailable());
    }

    private static String userVisibleResultId(String resultId, String resultKind, String visibility,
                                              boolean resultBodyAvailable) {
        return "USER".equals(visibility) && PUBLIC_RESULT_KINDS.contains(resultKind) && resultBodyAvailable
                ? resultId : null;
    }

    private static boolean validUserResult(RunProgressQueryStore.WorkItemResultFact result) {
        if (result == null || !"USER".equals(result.visibility()) || !PUBLIC_RESULT_KINDS.contains(result.kind())
                || result.resultId() == null || result.body() == null || result.bodySha256() == null
                || !result.bodySha256().equals(result.revisionSha256())) return false;
        byte[] body = result.body().getBytes(StandardCharsets.UTF_8);
        return body.length <= MAX_RESULT_BYTES && result.byteSize() == body.length
                && result.bodySha256().equals(CanonicalJson.sha256Hex(body));
    }

    private static String sourceKind(String value) {
        return switch (value == null ? "" : value) {
            case "PUBLISHED_EXPERT" -> "PUBLISHED_EXPERT";
            case "BUILTIN_GENERAL_PURPOSE" -> "BUILTIN_GENERAL_PURPOSE";
            default -> "RUNTIME_GENERATED";
        };
    }

    private static String safeRoleId(String roleId) {
        return roleId != null && roleId.matches("[A-Za-z0-9._:-]{1,64}") ? roleId : "readonly_analyst";
    }

    private static String safeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) return "协作成员";
        String value = displayName.replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
        if (value.isBlank()) return "协作成员";
        return value.length() > 128 ? value.substring(0, 128) : value;
    }

    private static RunProgressProjection.Team team(RunProgressQueryStore.TeamFact fact) {
        List<RunProgressProjection.MemberSummary> members = fact.members().stream().map(member -> {
            String state = switch (member.state() == null ? "" : member.state()) {
                case "RUNNING" -> "RUNNING";
                case "IDLE", "ENDED" -> "IDLE";
                case "STOPPED" -> "STOPPED";
                default -> "UNKNOWN";
            };
            boolean hasFact = member.lastTransitionOrdinal() > 0;
            return new RunProgressProjection.MemberSummary(safeRoleId(member.roleId()),
                    safeDisplayName(member.displayName()), "LEAD".equals(member.kind()) ? "LEAD" : "WORKER",
                    hasFact ? state : "UNKNOWN", member.lastTransitionAt(), hasFact ? "DURABLE_EVENT" : "NO_FACT");
        }).toList();
        List<RunProgressProjection.ActionBudget> budgets = fact.actionBudgets().stream()
                .filter(action -> List.of("TASK_CREATED", "MESSAGE_RECIPIENT").contains(action.actionType()))
                .map(action -> new RunProgressProjection.ActionBudget(action.actionType(), action.reserved(), null))
                .toList();
        String state = switch (fact.state() == null ? "" : fact.state()) {
            case "RUNNING" -> "RUNNING";
            case "COMPLETED" -> "COMPLETED";
            case "RECOVERY_REQUIRED" -> "RECOVERY_REQUIRED";
            default -> "UNKNOWN";
        };
        String reason = SAFE_TEAM_RECOVERY_REASONS.contains(fact.recoveryReasonCode())
                ? fact.recoveryReasonCode() : null;
        return new RunProgressProjection.Team(fact.executionId(), state, fact.startedAt(), fact.completedAt(),
                reason, members, budgets);
    }

    private static String version(String runId, RunProgressQueryStore.SnapshotFacts facts,
                                  RunProgressProjection.Summary summary,
                                  RunProgressProjection.DelegationBudget budget,
                                  List<RunProgressProjection.WorkItemSummary> allItems, boolean hasMore,
                                  RunProgressProjection.Team team, boolean available) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("runId", runId);
        value.put("runtimeProfile", facts.runtimeProfile());
        value.put("runState", facts.runState());
        value.put("updatedAt", instant(facts.updatedAt()));
        value.put("available", available);
        value.put("summary", List.of(summary.workItemTotal(), summary.activeCount(), summary.returnedCount(),
                summary.acceptedCount(), summary.revisionRequestedCount(), summary.rejectedCount(), summary.unknownCount()));
        value.put("budget", budget == null ? null : java.util.Arrays.asList(budget.invocationsReserved(),
                budget.invocationLimit(), budget.activeInvocations(), budget.parallelLimit()));
        value.put("items", allItems.stream().map(RunProgressQueryService::workItemVersion).toList());
        value.put("hasMoreWorkItems", hasMore);
        value.put("team", team == null ? null : teamVersion(team));
        return CanonicalJson.sha256(value);
    }

    private static Object workItemVersion(RunProgressProjection.WorkItemSummary item) {
        return java.util.Arrays.asList(item.workItemRef(), item.displayLabel(), item.roleId(),
                item.executorDisplayName(), item.sourceKind(), item.required(), item.assignmentRevision(), item.state(),
                instant(item.createdAt()), instant(item.updatedAt()), item.blockingDependencyCount(),
                revisionVersion(item.latestRevision()));
    }

    private static Object revisionVersion(RunProgressProjection.RevisionSummary revision) {
        return List.of(revision.assignmentRevision(), revision.executionState(), revision.formatStatus(),
                revision.reviewStatus(), revision.resultAvailability(), value(revision.resultId()),
                instant(revision.createdAt()), instant(revision.completedAt()));
    }

    private static Object teamVersion(RunProgressProjection.Team team) {
        return List.of(team.executionId(), team.state(), instant(team.startedAt()), instant(team.completedAt()),
                value(team.safeStopReasonCode()), team.memberSummaries().stream().map(member -> List.of(
                        member.roleId(), member.displayName(), member.kind(), member.state(),
                        instant(member.lastTransitionAt()), member.stateSource())).toList(),
                team.actionBudgets().stream().map(action -> List.of(action.actionType(), action.reserved(),
                        action.limit() == null ? -1 : action.limit())).toList());
    }

    private static String instant(Instant value) { return value == null ? "" : value.toString(); }
    private static String value(String value) { return value == null ? "" : value; }
    private static String cleanVersion(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static int limit(Integer requested) {
        int value = requested == null ? DEFAULT_LIMIT : requested;
        if (value < 1 || value > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 100");
        return value;
    }

    private static String encode(UserId owner, RunId runId, String filter,
                                 RunProgressQueryStore.WorkItemFact item) {
        String raw = String.join("\n", "v1", Long.toString(owner.value()), runId.value(), filter,
                Long.toString(item.createdAt().toEpochMilli()), item.workItemRef());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static RunProgressQueryStore.Position decode(String cursor, UserId owner, RunId runId, String filter) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > 2048) throw new IllegalArgumentException("cursor is invalid");
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 6 || !"v1".equals(parts[0]) || !Long.toString(owner.value()).equals(parts[1])
                    || !runId.value().equals(parts[2]) || !filter.equals(parts[3])
                    || !WORK_ITEM_REF.matcher(parts[5]).matches()) throw new IllegalArgumentException("cursor does not match the current query");
            return new RunProgressQueryStore.Position(Instant.ofEpochMilli(Long.parseLong(parts[4])), parts[5]);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("cursor is invalid", invalid);
        }
    }

    public record ProgressResult(RunProgressProjection projection, boolean notModified) { }

    public static final class RunProgressNotFoundException extends RuntimeException { }
}
