package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.kernel.identity.TenantId;
import java.util.Optional;
import java.util.List;

/** 解析租户属下的员工及其当前已发布的定义。 */
public interface EmployeeCatalog {
    Optional<PublishedEmployee> findPublished(TenantId tenantId, long employeeId);
    default List<DigitalEmployee> listEnabled(TenantId tenantId) { return List.of(); }
}
