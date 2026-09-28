package com.haizhuo.brain.runtime.agentscope.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.runtime.agentscope.TestRequests;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionWorkspaceMaterializerTest {

    @TempDir
    Path tempDir;

    @Test
    void materializesAgentsFileUnderBundleHashDirectory() throws Exception {
        DefinitionWorkspaceMaterializer materializer = new DefinitionWorkspaceMaterializer(tempDir);

        Path directory = materializer.materialize(TestRequests.definition("bundle-1", List.of()));

        assertEquals(tempDir.resolve("bundle-1"), directory);
        assertEquals("你是海卓数字员工。", Files.readString(directory.resolve("AGENTS.md")));
    }

    @Test
    void repeatedMaterializationIsIdempotent() throws Exception {
        DefinitionWorkspaceMaterializer materializer = new DefinitionWorkspaceMaterializer(tempDir);
        Path first = materializer.materialize(TestRequests.definition("bundle-1", List.of()));
        Files.writeString(first.resolve("AGENTS.md"), " externally tuned ");

        Path second = materializer.materialize(TestRequests.definition("bundle-1", List.of()));

        assertEquals(first, second);
        assertTrue(Files.readString(second.resolve("AGENTS.md")).contains("externally tuned"),
                "content-addressed reuse must not rewrite an existing definition workspace");
    }
}
