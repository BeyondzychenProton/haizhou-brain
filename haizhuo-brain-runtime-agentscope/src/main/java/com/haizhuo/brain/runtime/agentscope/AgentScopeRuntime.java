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
import io.agentscope.core.ReActAgent;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
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
                throw new IllegalStateException("LLM API key is missing; configure config/application-meeting-mock.yml");
            String provider = request.modelProvider() == null ? properties.provider() : request.modelProvider();
            var model = switch (provider == null ? "" : provider.toLowerCase(java.util.Locale.ROOT)) {
                case "openai", "openai-compatible" -> {
                    if (properties.baseUrl() == null || properties.baseUrl().isBlank())
                        throw new IllegalStateException("LLM base-url is missing; configure config/application-meeting-mock.yml");
                    yield OpenAIChatModel.builder().apiKey(properties.apiKey()).modelName(request.modelName())
                            .baseUrl(properties.baseUrl()).stream(false).build();
                }
                case "dashscope" -> {
                    var builder = DashScopeChatModel.builder().apiKey(properties.apiKey()).modelName(request.modelName()).stream(false);
                    if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) builder.baseUrl(properties.baseUrl());
                    yield builder.build();
                }
                default -> throw new IllegalStateException("Unsupported LLM provider: " + provider);
            };
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new MeetingRoomTools(toolGateway, request));
            try (ReActAgent agent = ReActAgent.builder().name("meeting-room-agent").description("Mock meeting-room reservation agent")
                    .sysPrompt(request.instructions()).model(model).toolkit(toolkit).maxIters(6).build()) {
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
