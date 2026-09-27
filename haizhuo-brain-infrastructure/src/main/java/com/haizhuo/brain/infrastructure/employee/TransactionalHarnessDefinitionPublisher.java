package com.haizhuo.brain.infrastructure.employee;

import com.haizhuo.brain.platform.employee.AgentDefinitionManagementAudit;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import com.haizhuo.brain.platform.employee.runtime.DefaultHarnessDefinitionPublisher;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleCompiler;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleRepository;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tx-01 的事务边界（规格 §64）：定义版本 + 运行时能力包 + 当前已发布指针
 * 一起提交或一起回滚。各仓储以 REQUIRED 传播加入该事务；platform 模块自身不带 Spring。
 */
@Component
public class TransactionalHarnessDefinitionPublisher implements HarnessDefinitionPublisher {
    private final DefaultHarnessDefinitionPublisher delegate;

    public TransactionalHarnessDefinitionPublisher(AgentDefinitionRepository definitions,
                                                   HarnessDefinitionBundleCompiler compiler,
                                                   HarnessDefinitionBundleRepository bundles) {
        this.delegate = new DefaultHarnessDefinitionPublisher(definitions, compiler, bundles);
    }

    @Override
    @Transactional
    public PublishedEmployee publish(long employeeId, int expectedDraftRevision,
                                     AgentDefinitionManagementAudit audit) {
        return delegate.publish(employeeId, expectedDraftRevision, audit);
    }
}
