package com.haizhuo.brain.runtime.agentscope;

import com.haizhuo.brain.runtime.agentscope.context.RuntimeContextFactory;
import com.haizhuo.brain.runtime.agentscope.event.AgentScopeEventTranslator;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessRuntimeTemplate;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessTemplateCache;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.event.AgentToolSuspendedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionInput;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.ExternalToolResultExecutionInput;
import com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 运行时的最终职责（规格 §30）：1. 取 HarnessRuntimeTemplate；2. 创建 RuntimeContext；
 * 3. 构建输入；4. 走 HarnessAgent 事件流；5. 翻译事件。
 * 这里不做数据库查询、授权、凭据、工具副作用或版本解析。
 * 企业工具调用以 RequireExternalExecutionEvent 的形式浮现，并被翻译成
 * AgentToolSuspendedEvent——运行时绝不执行它们（§34，I-06）。
 */
public class AgentScopeRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeRuntime.class);

    private final HarnessTemplateCache templateCache;
    private final RuntimeContextFactory contextFactory;
    private final AgentScopeEventTranslator translator;

    public AgentScopeRuntime(HarnessTemplateCache templateCache, RuntimeContextFactory contextFactory,
                             AgentScopeEventTranslator translator) {
        this.templateCache = Objects.requireNonNull(templateCache);
        this.contextFactory = Objects.requireNonNull(contextFactory);
        this.translator = Objects.requireNonNull(translator);
    }

    @Override
    public Flux<BrainAgentEvent> execute(AgentExecutionRequest request) {
        return Flux.defer(() -> {
                    HarnessRuntimeTemplate template = templateCache.getOrBuild(request.definition());
                    RuntimeContext context = contextFactory.create(request);
                    Flux<AgentEvent> events = stream(template, request.input(), context);
                    // 一旦已经翻译出挂起事件，同一条流末尾的完成事件
                    // 就不能再把 Run 翻回 SUCCEEDED（§34）。
                    AtomicBoolean suspended = new AtomicBoolean();
                    return events.concatMap(event -> translator.translate(request.runId(), event))
                            .doOnNext(event -> {
                                if (event instanceof AgentToolSuspendedEvent) {
                                    suspended.set(true);
                                }
                            })
                            .filter(event -> !(suspended.get() && event instanceof AgentRunCompletedEvent));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    Throwable root = error;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    // 中文注释：只记录异常类型，不把上游响应正文、请求头或模型密钥写进日志。
                    log.error("AgentScope execution failed; errorType={}, rootCauseType={}",
                            error.getClass().getSimpleName(), root.getClass().getSimpleName());
                    return Mono.just(new AgentRunFailedEvent(request.runId(),
                            "AgentScope执行异常类型：" + root.getClass().getSimpleName()));
                });
    }

    private Flux<AgentEvent> stream(HarnessRuntimeTemplate template, AgentExecutionInput input,
                                    RuntimeContext context) {
        if (input instanceof UserPromptExecutionInput prompt) {
            return template.agent().streamEvents(prompt.content(), context);
        }
        if (input instanceof ExternalToolResultExecutionInput results) {
            return template.agent().streamEvents(toToolResultMessage(results), context);
        }
        return Flux.error(new IllegalArgumentException(
                "Unsupported execution input type: " + input.getClass().getName()));
    }

    /**
     * 工具结果恢复（规格 §32）：保留原始的 toolUseId/toolName，
     * 以便 AgentScope 把每个结果匹配到被挂起的调用。重复投递由平台侧的
     * result_delivery_state 守卫（§45.1），不在这里处理。
     */
    private static ToolResultMessage toToolResultMessage(ExternalToolResultExecutionInput input) {
        List<ToolResultBlock> blocks = input.results().stream()
                .map(result -> ToolResultBlock.builder()
                        .id(result.toolUseId())
                        .name(result.toolName())
                        .output(TextBlock.builder().text(result.content()).build())
                        .state(result.success() ? ToolResultState.SUCCESS : ToolResultState.ERROR)
                        .build())
                .toList();
        return new ToolResultMessage(blocks);
    }
}
