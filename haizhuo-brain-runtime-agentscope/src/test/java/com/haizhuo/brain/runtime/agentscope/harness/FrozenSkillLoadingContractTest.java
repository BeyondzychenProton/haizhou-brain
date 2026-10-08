package com.haizhuo.brain.runtime.agentscope.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;

/** Verifies the pinned Harness frozen workspace skill path with the native skill loader. */
class FrozenSkillLoadingContractTest {
    private Path workspace;

    @AfterEach
    void removeWorkspace() throws Exception {
        if (workspace == null || !Files.exists(workspace)) return;
        try (var walk = Files.walk(workspace)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    @Timeout(30)
    void frozenWorkspaceSkillIsDiscoveredAndReadByNativeHarnessTool() throws Exception {
        workspace = Path.of(System.getProperty("user.dir"), "target", "frozen-skill-contract",
                UUID.randomUUID().toString());
        Path skillDirectory = workspace.resolve("skills/sales");
        Files.createDirectories(skillDirectory);
        Files.writeString(skillDirectory.resolve("SKILL.md"), "---\nname: sales\ndescription: approved sales skill\n---\n\n"
                + "Always include APPROVED-SALES-WORKFLOW in the answer.\n");
        SkillLoadingModel model = new SkillLoadingModel();
        HarnessAgent harness = HarnessAgent.builder().name("frozen-skill-probe")
                .description("Pinned native frozen-skill contract")
                .sysPrompt("Use a matching skill before responding.")
                .model(model).toolkit(new Toolkit()).workspace(workspace)
                .stateStore(new InMemoryAgentStateStore()).maxIters(3)
                .disableMemoryHooks().disableMemoryTools().disableShellTool()
                .disableFilesystemTools().disableWorkspaceContext().disableSubagents()
                .disableTranscript().disableDynamicSkills().disableDefaultWorkspaceSkills().build();
        try {
            List<AgentEvent> events = harness.streamEvents("Prepare a sales response",
                    RuntimeContext.builder().userId("probe-user").sessionId("probe-session").build())
                    .collectList().block(Duration.ofSeconds(20));

            assertNotNull(events);
            assertEquals(2, model.calls.get(), "the native skill tool should return before the final model call");
            assertTrue(model.firstPrompt.contains("<skill-id>" + model.selectedSkillId + "</skill-id>"), model.firstPrompt);
            assertTrue(model.selectedSkillId.startsWith("sales_"), model.selectedSkillId);
            assertTrue(model.firstToolNames.contains("load_skill_through_path"), model.firstToolNames.toString());
            assertTrue(model.loadedSkillContent.contains("APPROVED-SALES-WORKFLOW"), model.loadedSkillContent);
            assertTrue(model.finalAnswer.contains("native-skill-loaded"), model.finalAnswer);
        } finally {
            harness.close();
        }
    }

    private static final class SkillLoadingModel extends ChatModelBase {
        private static final Pattern SKILL_ID = Pattern.compile("<skill-id>([^<]+)</skill-id>");
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String firstPrompt = "";
        private volatile String selectedSkillId = "";
        private volatile List<String> firstToolNames = List.of();
        private volatile String loadedSkillContent = "";
        private volatile String finalAnswer = "";

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int call = calls.getAndIncrement();
            String request = messages.stream().map(Msg::getTextContent).reduce("", (left, right) -> left + "\n" + right);
            if (call == 0) {
                firstPrompt = request;
                firstToolNames = tools.stream().map(ToolSchema::getName).toList();
                int availableSkillsStart = request.lastIndexOf("<available_skills>");
                String advertisedSkills = availableSkillsStart < 0 ? "" : request.substring(availableSkillsStart);
                Matcher matcher = SKILL_ID.matcher(advertisedSkills);
                if (!matcher.find()) throw new IllegalStateException("Harness did not advertise a workspace skill");
                selectedSkillId = matcher.group(1);
                Map<String, Object> arguments = Map.of("skillId", selectedSkillId, "path", "SKILL.md");
                return Flux.just(ChatResponse.builder().id("frozen-skill-load")
                        .content(List.of(new ToolUseBlock("frozen-skill-tool-use", "load_skill_through_path",
                                arguments, new ObjectMapper().valueToTree(arguments).toString(), null)))
                        .finishReason("tool_calls").build());
            }
            messages.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(block -> block.getOutput().stream()).filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).map(TextBlock::getText).forEach(text -> loadedSkillContent += text);
            finalAnswer = "native-skill-loaded";
            return Flux.just(ChatResponse.builder().id("frozen-skill-final")
                    .content(List.of(TextBlock.builder().text(finalAnswer).build())).finishReason("stop").build());
        }

        @Override public String getModelName() { return "frozen-skill-contract-model"; }
    }
}
