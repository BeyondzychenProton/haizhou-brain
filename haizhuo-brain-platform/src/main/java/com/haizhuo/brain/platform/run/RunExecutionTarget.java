package com.haizhuo.brain.platform.run;

import java.util.Objects;

/** Immutable executor selected from the Session's frozen team bundle for one Run. */
public record RunExecutionTarget(RunExecutionMode mode, String roleId, long employeeId,
                                 long definitionVersionId, String roleSlotId) {
    public static final String COORDINATOR_ROLE = "coordinator";

    public RunExecutionTarget {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(roleId);
        if (roleId.isBlank() || employeeId <= 0 || definitionVersionId <= 0)
            throw new IllegalArgumentException("Run executor identity is required");
        if (COORDINATOR_ROLE.equals(roleId) != (roleSlotId == null))
            throw new IllegalArgumentException("Coordinator and member role-slot mapping is inconsistent");
    }

    public static RunExecutionTarget coordinator(long employeeId, long definitionVersionId) {
        return new RunExecutionTarget(RunExecutionMode.DIRECT, COORDINATOR_ROLE,
                employeeId, definitionVersionId, null);
    }
}
