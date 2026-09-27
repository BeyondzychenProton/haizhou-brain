package com.haizhuo.brain.platform.employee.runtime;

import java.util.Optional;

/** 不可变运行时能力包的持久化边界。能力包只允许插入。 */
public interface HarnessDefinitionBundleRepository {
    HarnessDefinitionBundle save(HarnessDefinitionBundle bundle);

    Optional<HarnessDefinitionBundle> findByDefinitionVersionId(long definitionVersionId);

    Optional<HarnessDefinitionBundle> findByBundleHash(String bundleHash);
}
