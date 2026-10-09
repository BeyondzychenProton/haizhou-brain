package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.model.RuntimeModelConnectionRef;
import java.util.Optional;

/** 不可变定义与 Run 模型连接引用的持久化边界。 */
public interface ModelConnectionBindingStore {
    /** 仅复制已发布草稿修订所选的精确且仍处于活动状态的引用。 */
    void freezeDefinitionVersionBinding(long employeeId, int draftRevision, long definitionVersionId);

    /** 仅当修订摘要和 provider 仍然匹配时返回不可变引用。 */
    Optional<RuntimeModelConnectionRef> findDefinitionVersionBinding(long definitionVersionId,
                                                                      String expectedProvider);

    /** 插入不可变的 Run/执行者绑定，或核验此前已存在的绑定完全相同。 */
    RuntimeModelConnectionRef freezeRunBinding(RunId runId, String executorRoleId,
                                               long executorDefinitionVersionId, String expectedProvider,
                                               RuntimeModelConnectionRef reference);
}
