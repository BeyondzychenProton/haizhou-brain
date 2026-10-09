package com.haizhuo.brain.infrastructure.employee;

import com.haizhuo.brain.platform.employee.AgentDefinitionManagementAudit;
import com.haizhuo.brain.platform.employee.ModelConnectionBindingStore;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 在现有发布事务中加入所选的不可变模型连接修订。 */
@Component
@Primary
public class ModelConnectionBoundHarnessDefinitionPublisher implements HarnessDefinitionPublisher {
    private final HarnessDefinitionPublisher delegate;
    private final ModelConnectionBindingStore bindings;

    public ModelConnectionBoundHarnessDefinitionPublisher(
            @Qualifier("transactionalHarnessDefinitionPublisher") HarnessDefinitionPublisher delegate,
            ModelConnectionBindingStore bindings) {
        this.delegate = delegate;
        this.bindings = bindings;
    }

    @Override
    @Transactional
    public PublishedEmployee publish(long employeeId, int expectedDraftRevision,
                                     AgentDefinitionManagementAudit audit) {
        PublishedEmployee published = delegate.publish(employeeId, expectedDraftRevision, audit);
        if (published.definition().employeeId() != employeeId)
            throw new IllegalStateException("Published definition owner does not match the requested employee");
        bindings.freezeDefinitionVersionBinding(employeeId, expectedDraftRevision, published.definition().id());
        return published;
    }
}
