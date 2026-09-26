package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.kernel.identity.TenantId;
import java.util.Optional;

/** Resolves a tenant-owned employee and its current published definition. */
public interface EmployeeCatalog {
    Optional<PublishedEmployee> findPublished(TenantId tenantId, long employeeId);
}
