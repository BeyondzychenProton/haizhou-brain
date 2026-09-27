package com.haizhuo.brain.platform.employee.runtime;

import com.haizhuo.brain.platform.employee.AgentDefinitionDraft;
import com.haizhuo.brain.platform.employee.AgentDefinitionManagementAudit;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import java.util.Objects;

/**
 * 发布编排（Tx-01，规格 §64）：先发布不可变的定义版本，再编译并持久化其运行时能力包。
 * 底层仓储会加入调用方的事务，因此把这个 bean 放进事务边界即可让整次发布原子化。
 * 幂等重放（同一发布请求 ID）时复用既有版本及其既有能力包，不重新编译。
 */
public class DefaultHarnessDefinitionPublisher implements HarnessDefinitionPublisher {
    private final AgentDefinitionRepository definitions;
    private final HarnessDefinitionBundleCompiler compiler;
    private final HarnessDefinitionBundleRepository bundles;

    public DefaultHarnessDefinitionPublisher(AgentDefinitionRepository definitions,
                                             HarnessDefinitionBundleCompiler compiler,
                                             HarnessDefinitionBundleRepository bundles) {
        this.definitions = Objects.requireNonNull(definitions);
        this.compiler = Objects.requireNonNull(compiler);
        this.bundles = Objects.requireNonNull(bundles);
    }

    @Override
    public PublishedEmployee publish(long employeeId, int expectedDraftRevision,
                                     AgentDefinitionManagementAudit audit) {
        // 先读出草稿用于编译；publish() 会在草稿行锁下重新校验同一修订，
        // 因此编译内容与最终发布的版本一致。
        AgentDefinitionDraft draft = definitions.findDraft(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Agent draft not found"));
        if (draft.draftRevision() != expectedDraftRevision) {
            throw new IllegalStateException("Draft revision is stale");
        }
        PublishedEmployee published = definitions.publish(employeeId, expectedDraftRevision, audit);
        if (bundles.findByDefinitionVersionId(published.definition().id()).isEmpty()) {
            HarnessDefinitionBundle bundle = compiler.compile(published.definition().id(),
                    published.employee().displayName(), draft);
            bundles.save(bundle);
        }
        return published;
    }
}
