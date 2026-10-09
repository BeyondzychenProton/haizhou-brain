package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 管理员专用员工生命周期和不可变版本查询的持久化边界。 */
public interface EmployeeAdministrationStore {
    Optional<EmployeeSummary> findEmployee(long employeeId);

    EmployeePage listEmployees(String query, Boolean enabled, Boolean published, String cursor, int limit);

    CreatedEmployee createEmployee(String employeeCode, String displayName, long actorId,
                                    String requestId, String requestHash, String reason);

    EmployeeSummary updateEmployee(long employeeId, long expectedRowVersion, String displayName,
                                   long actorId, String requestId, String requestHash, String reason);

    EmployeeSummary setEmployeeEnabled(long employeeId, long expectedRowVersion, boolean enabled,
                                       long actorId, String requestId, String requestHash, String reason);

    EmployeeVersionPage listVersions(long employeeId, String cursor, int limit);

    Optional<EmployeeVersionDetail> findVersion(long employeeId, long versionId);

    record EmployeeSummary(long employeeId, String employeeCode, String displayName, boolean enabled,
                           boolean published, Long currentPublishedVersionId, long rowVersion, Instant createdAt) {}

    record CreatedEmployee(EmployeeSummary employee, AgentDefinitionDraft draft) {}

    record EmployeePage(List<EmployeeSummary> items, String nextCursor, boolean hasMore) {
        public EmployeePage { items = List.copyOf(items); }
    }

    record EmployeeVersionSummary(long versionId, long employeeId, int versionNo, String contentHash,
                                  RuntimeProfile profile, long publishedBy, Instant publishedAt, boolean current) {}

    record EmployeeVersionPage(List<EmployeeVersionSummary> items, String nextCursor, boolean hasMore) {
        public EmployeeVersionPage { items = List.copyOf(items); }
    }

    record CapabilityDetail(String capabilityCode, String revision, String displayName,
                            String capabilityType, String contentHash, boolean enabled) {}

    record MemberDetail(String roleId, long employeeId, String employeeCode, String displayName,
                        long definitionVersionId, int versionNo, String contentHash,
                        RuntimeProfile profile, int steps, boolean employeeEnabled) {}

    record EmployeeVersionDetail(EmployeeVersionSummary summary, String instructions, String modelProvider,
                                 String modelName, EmployeeRuntimeConfiguration configuration,
                                 List<CapabilityDetail> capabilities, List<MemberDetail> members,
                                 boolean runtimeBundleAvailable) {
        public EmployeeVersionDetail {
            capabilities = List.copyOf(capabilities);
            members = List.copyOf(members);
        }
    }
}
