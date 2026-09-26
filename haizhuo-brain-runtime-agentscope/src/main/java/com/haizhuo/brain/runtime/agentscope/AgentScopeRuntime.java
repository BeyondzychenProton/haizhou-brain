package com.haizhuo.brain.runtime.agentscope;

import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.agentscope.tool.MeetingRoomTools;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

@Component
@Profile("meeting-mock | test")
public class AgentScopeRuntime implements AgentRuntime {
    private final AgentScopeRuntimeProperties properties;
    private final ToolExecutionGateway toolGateway;

    public AgentScopeRuntime(AgentScopeRuntimeProperties properties, ToolExecutionGateway toolGateway) {
        this.properties = properties; this.toolGateway = toolGateway;
    }

    @Override
    public Flux<BrainAgentEvent> execute(AgentExecutionRequest request) {
        return Mono.fromCallable(() -> {
            if (properties.apiKey() == null || properties.apiKey().isBlank())
                throw new IllegalStateException("DashScope API key is missing; set DASHSCOPE_API_KEY to execute a Run");
            var modelBuilder = DashScopeChatModel.builder().apiKey(properties.apiKey()).modelName(request.modelName()).stream(false);
            if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) modelBuilder.baseUrl(properties.baseUrl());
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new MeetingRoomTools(toolGateway, request));
            try (HarnessAgent agent = HarnessAgent.builder().name("meeting-room-agent").description("Mock meeting-room reservation agent")
                    .sysPrompt(request.instructions()).model(modelBuilder.build()).toolkit(toolkit).maxIters(6)
                    .disableFilesystemTools().disableShellTool().disableMemoryTools().disableMemoryHooks()
                    .disableTranscript().disableSessionPersistence().disableWorkspaceContext().disableSubagents()
                    .disableDynamicSkills().disableDefaultWorkspaceSkills().disableToolsConfig().build()) {
                RuntimeContext context = RuntimeContext.builder().sessionId(request.sessionId().value())
                        .userId(Long.toString(request.userId().value())).put(AgentExecutionRequest.class, request).build();
                var answer = agent.call(request.prompt(), context).block();
                String text = answer == null ? "AgentScope returned no answer" : answer.getTextContent();
                return (BrainAgentEvent) new AgentRunCompletedEvent(request.runId(), text == null ? "" : text);
            }
        }).subscribeOn(Schedulers.boundedElastic()).<BrainAgentEvent>map(event -> event)
                .onErrorResume(error -> Mono.just(new AgentRunFailedEvent(request.runId(), error.getMessage() == null ? "Agent execution failed" : error.getMessage()))).flux();
    }
}
