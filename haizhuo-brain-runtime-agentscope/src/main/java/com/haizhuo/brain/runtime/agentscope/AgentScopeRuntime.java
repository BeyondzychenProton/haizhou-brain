package com.haizhuo.brain.runtime.agentscope;

import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCancelledEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

// 中文注释：真实业务网关与可信用户尚未接入，因此此运行时暂不注册为可自动调用的 Spring Bean。
public class AgentScopeRuntime implements AgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentScopeRuntime.class);
    private final AgentScopeRuntimeProperties properties;
    private final ToolExecutionGateway toolGateway;
    private final AgentScopeToolkitAssembler toolkitAssembler;
    private final RunControlInbox controlInbox;

    public AgentScopeRuntime(AgentScopeRuntimeProperties properties, ToolExecutionGateway toolGateway,
                             AgentScopeToolkitAssembler toolkitAssembler, RunControlInbox controlInbox) {
        this.properties = properties; this.toolGateway = toolGateway; this.toolkitAssembler = toolkitAssembler; this.controlInbox = controlInbox;
    }

    @Override
    public Flux<BrainAgentEvent> execute(AgentExecutionRequest request) {
        return Mono.fromCallable(() -> {
            if (properties.apiKey() == null || properties.apiKey().isBlank())
                throw new IllegalStateException("LLM API key is missing from the configured agent model");
            String provider = request.modelProvider() == null ? properties.provider() : request.modelProvider();
            var model = switch (provider == null ? "" : provider.toLowerCase(java.util.Locale.ROOT)) {
                case "openai", "openai-compatible" -> {
                    if (properties.baseUrl() == null || properties.baseUrl().isBlank())
                        throw new IllegalStateException("LLM base-url is missing from the configured agent model");
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
            var toolkit = toolkitAssembler.assemble(request, toolGateway);
            String employeeDescription = request.employeeName() == null ? "Digital employee" : request.employeeName();
            try (ReActAgent agent = ReActAgent.builder().name("digital-employee").description(employeeDescription)
                    .sysPrompt(request.instructions()).model(model).toolkit(toolkit).maxIters(6)
                    .middleware(new RunControlMiddleware(controlInbox)).build()) {
                RuntimeContext context = RuntimeContext.builder().sessionId(request.sessionId().value())
                        .userId(Long.toString(request.userId().value())).put(AgentExecutionRequest.class, request).build();
                var answer = agent.call(request.prompt(), context).block();
                if (controlInbox.isCancellationRequested(request.runId()))
                    return (BrainAgentEvent) new AgentRunCancelledEvent(request.runId(), "运行已在安全检查点取消");
                String text = answer == null ? "AgentScope returned no answer" : answer.getTextContent();
                return (BrainAgentEvent) new AgentRunCompletedEvent(request.runId(), text == null ? "" : text);
            }
        }).subscribeOn(Schedulers.boundedElastic()).<BrainAgentEvent>map(event -> event)
                .onErrorResume(error -> {
                    Throwable root = error;
                    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    // 中文注释：只记录异常类型，不把上游响应正文、请求头或模型密钥写进日志。
                    log.error("AgentScope execution failed; errorType={}, rootCauseType={}",
                            error.getClass().getSimpleName(), root.getClass().getSimpleName());
                    return Mono.just(new AgentRunFailedEvent(request.runId(),
                            "AgentScope执行异常类型：" + root.getClass().getSimpleName()));
                }).flux();
    }
}
