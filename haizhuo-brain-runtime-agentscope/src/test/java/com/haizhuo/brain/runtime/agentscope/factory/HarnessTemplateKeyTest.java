package com.haizhuo.brain.runtime.agentscope.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.haizhuo.brain.runtime.agentscope.TestRequests;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessTemplateKeyTest {

    @Test
    void identicalContentProducesIdenticalKeys() {
        RuntimeDefinitionSnapshot first = TestRequests.definition("bundle-1", List.of(TestRequests.tool(1L, "meeting.reserve")));
        RuntimeDefinitionSnapshot second = TestRequests.definition("bundle-1", List.of(TestRequests.tool(1L, "meeting.reserve")));

        assertEquals(HarnessTemplateKey.from(first), HarnessTemplateKey.from(second));
    }

    @Test
    void definitionVersionIdDoesNotAffectTheKey() {
        RuntimeDefinitionSnapshot v1 = TestRequests.definition("bundle-1", List.of());
        RuntimeDefinitionSnapshot v2 = new RuntimeDefinitionSnapshot(2L, v1.employeeName(), v1.instructions(),
                v1.modelProvider(), v1.modelName(), v1.maxIterations(), v1.definitionBundleHash(),
                v1.workspaceProjectionKey(), v1.workspaceContentHash(), v1.toolCatalog());

        assertEquals(HarnessTemplateKey.from(v1), HarnessTemplateKey.from(v2));
    }

    @Test
    void contentChangesProduceDifferentKeys() {
        RuntimeDefinitionSnapshot base = TestRequests.definition("bundle-1", List.of(TestRequests.tool(1L, "meeting.reserve")));

        assertNotEquals(HarnessTemplateKey.from(base),
                HarnessTemplateKey.from(TestRequests.definition("bundle-2", base.toolCatalog())));
        assertNotEquals(HarnessTemplateKey.from(base),
                HarnessTemplateKey.from(TestRequests.definition("bundle-1", List.of())));
        assertNotEquals(HarnessTemplateKey.from(base),
                HarnessTemplateKey.from(new RuntimeDefinitionSnapshot(1L, base.employeeName(), base.instructions(),
                        "dashscope", base.modelName(), base.maxIterations(), base.definitionBundleHash(),
                        base.workspaceProjectionKey(), base.workspaceContentHash(), base.toolCatalog())));
    }
}
