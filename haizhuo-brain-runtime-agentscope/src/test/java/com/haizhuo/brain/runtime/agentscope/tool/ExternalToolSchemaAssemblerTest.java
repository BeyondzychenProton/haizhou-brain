package com.haizhuo.brain.runtime.agentscope.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.runtime.agentscope.TestRequests;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalToolSchemaAssemblerTest {

    @Test
    void registersEveryCatalogEntryAsSchemaOnlyExternalTool() {
        Toolkit toolkit = ExternalToolSchemaAssembler.assemble(List.of(
                TestRequests.tool(1L, "meeting.reserve"), TestRequests.tool(2L, "meeting.cancel")));

        assertEquals(List.of("meeting.reserve", "meeting.cancel"),
                toolkit.getToolSchemas().stream().map(ToolSchema::getName).toList());
        assertTrue(toolkit.isExternalTool("meeting.reserve"));
        assertTrue(toolkit.isExternalTool("meeting.cancel"));
    }

    @Test
    void emptyCatalogYieldsEmptyToolkit() {
        Toolkit toolkit = ExternalToolSchemaAssembler.assemble(List.of());

        assertEquals(0, toolkit.getToolSchemas().size());
    }
}
