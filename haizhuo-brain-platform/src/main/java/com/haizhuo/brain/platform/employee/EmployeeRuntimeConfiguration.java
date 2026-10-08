package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.util.List;
import java.util.Objects;

/** Administrator-authored runtime intent; publication resolves it into immutable policy and references. */
public record EmployeeRuntimeConfiguration(int schemaVersion, RuntimeProfile profile, RuntimePolicy runtimePolicy,
                                           TeamConfiguration team, List<FixedMember> members) {
    public EmployeeRuntimeConfiguration {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(runtimePolicy);
        members = List.copyOf(members == null ? List.of() : members);
    }

    public static EmployeeRuntimeConfiguration legacyStable() {
        return new EmployeeRuntimeConfiguration(1, RuntimeProfile.LEGACY_STABLE,
                new RuntimePolicy(1, 0, 0, 30, false), null, List.of());
    }

    public static EmployeeRuntimeConfiguration singleSkilled(int maxIterations) {
        return new EmployeeRuntimeConfiguration(2, RuntimeProfile.SINGLE_SKILLED,
                new RuntimePolicy(maxIterations, 0, 0, 120, false), null, List.of());
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

    public record FixedMember(String roleId, long employeeId, long definitionVersionId, int steps) {}
}
