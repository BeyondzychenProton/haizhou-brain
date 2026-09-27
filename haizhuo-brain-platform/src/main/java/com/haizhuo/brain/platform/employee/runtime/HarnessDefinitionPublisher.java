package com.haizhuo.brain.platform.employee.runtime;

import com.haizhuo.brain.platform.employee.AgentDefinitionManagementAudit;
import com.haizhuo.brain.platform.employee.PublishedEmployee;

/**
 * 发布流水线（Tx-01，规格 §64）：校验、插入不可变定义版本、编译并持久化运行时能力包、
 * 切换“当前已发布”指针并写审计——全部在同一个事务内完成。
 */
public interface HarnessDefinitionPublisher {
    PublishedEmployee publish(long employeeId, int expectedDraftRevision, AgentDefinitionManagementAudit audit);
}
