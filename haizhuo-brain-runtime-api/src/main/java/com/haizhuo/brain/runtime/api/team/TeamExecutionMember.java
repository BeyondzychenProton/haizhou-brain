package com.haizhuo.brain.runtime.api.team;

/** Frozen platform identity for one native AgentScope Team session. */
public record TeamExecutionMember(
        String roleId,
        long employeeId,
        long definitionVersionId,
        String harnessSessionKey,
        Kind kind) {

    public TeamExecutionMember {
        if (roleId == null || roleId.isBlank()) throw new IllegalArgumentException("roleId is required");
        if (employeeId < 0 || definitionVersionId <= 0)
            throw new IllegalArgumentException("invalid frozen member identity");
        if (harnessSessionKey == null || harnessSessionKey.isBlank())
            throw new IllegalArgumentException("harnessSessionKey is required");
        if (kind == null) throw new IllegalArgumentException("kind is required");
    }

    public enum Kind { LEAD, WORKER }
}
