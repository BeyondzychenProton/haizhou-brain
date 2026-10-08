package com.haizhuo.brain.runtime.api.team;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Trusted Run-to-native-Team mapping created before any native task or message is written. */
public record TeamExecutionSnapshot(
        String teamExecutionId,
        RunId runId,
        UserId userId,
        SessionId sessionId,
        String attemptId,
        long fenceToken,
        String teamName,
        String namespace,
        long ownerDefinitionVersionId,
        List<TeamExecutionMember> members,
        Instant startedAt) {

    public TeamExecutionSnapshot {
        if (teamExecutionId == null || teamExecutionId.isBlank())
            throw new IllegalArgumentException("teamExecutionId is required");
        Objects.requireNonNull(runId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(sessionId);
        if (attemptId == null || attemptId.isBlank()) throw new IllegalArgumentException("attemptId is required");
        if (fenceToken <= 0) throw new IllegalArgumentException("fenceToken must be positive");
        if (teamName == null || teamName.isBlank() || namespace == null || namespace.isBlank())
            throw new IllegalArgumentException("native Team identity is required");
        if (ownerDefinitionVersionId <= 0) throw new IllegalArgumentException("owner version is required");
        members = List.copyOf(members == null ? List.of() : members);
        if (members.isEmpty() || members.stream().noneMatch(member -> member.kind() == TeamExecutionMember.Kind.LEAD))
            throw new IllegalArgumentException("one frozen Team lead is required");
        if (members.stream().filter(member -> member.kind() == TeamExecutionMember.Kind.LEAD).count() != 1)
            throw new IllegalArgumentException("exactly one Team lead is required");
        if (members.size() > 3) throw new IllegalArgumentException("Team roster is limited to one lead and two workers");
        if (members.stream().map(TeamExecutionMember::roleId).distinct().count() != members.size())
            throw new IllegalArgumentException("Team member role IDs must be unique");
        if (members.stream().map(TeamExecutionMember::harnessSessionKey).distinct().count() != members.size())
            throw new IllegalArgumentException("Team member sessions must be unique");
        Objects.requireNonNull(startedAt);
    }
}
