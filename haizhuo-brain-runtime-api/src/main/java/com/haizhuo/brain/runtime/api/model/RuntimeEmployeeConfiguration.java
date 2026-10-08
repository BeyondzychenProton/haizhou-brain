package com.haizhuo.brain.runtime.api.model;

import java.util.List;
import java.util.Objects;

/** Immutable, runtime-facing projection of an employee's published execution policy. */
public record RuntimeEmployeeConfiguration(int schemaVersion, RuntimeProfile profile,
                                           RuntimePolicy policy, TeamConfiguration team,
                                           List<FixedMember> members) {
    public RuntimeEmployeeConfiguration {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(policy);
        members = List.copyOf(members == null ? List.of() : members);
    }

    public static RuntimeEmployeeConfiguration legacyStable() {
        return new RuntimeEmployeeConfiguration(1, RuntimeProfile.LEGACY_STABLE,
                new RuntimePolicy(1, 0, 0, 30, false), null, List.of());
    }

    public record RuntimePolicy(int maxIterations, int maxParallelDelegations,
                                int maxExpertInvocationsPerRun, int syncTimeoutSeconds,
                                boolean memoryEnabled) {}

    public record TeamConfiguration(String defaultRoleId, List<String> userSelectableRoles,
                                    List<RoleDelegation> allowedDelegations) {
        public TeamConfiguration {
            userSelectableRoles = List.copyOf(userSelectableRoles == null ? List.of() : userSelectableRoles);
            allowedDelegations = List.copyOf(allowedDelegations == null ? List.of() : allowedDelegations);
        }
    }

    public record RoleDelegation(String fromRoleId, String toRoleId) {}

    public record FixedMember(String roleId, long employeeId, long definitionVersionId, int steps,
                              RuntimeDefinitionSnapshot definition) {
        public FixedMember(String roleId, long employeeId, long definitionVersionId, int steps) {
            this(roleId, employeeId, definitionVersionId, steps, null);
        }

        public FixedMember {
            if (roleId == null || roleId.isBlank()) throw new IllegalArgumentException("roleId is required");
            if (employeeId <= 0 || definitionVersionId <= 0 || steps <= 0)
                throw new IllegalArgumentException("fixed member identity and steps must be positive");
            if (definition != null && definition.definitionVersionId() != definitionVersionId)
                throw new IllegalArgumentException("member runtime snapshot version does not match its frozen version");
        }
    }
}
