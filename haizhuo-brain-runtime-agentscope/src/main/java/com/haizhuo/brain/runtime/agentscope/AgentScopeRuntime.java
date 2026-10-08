package com.haizhuo.brain.runtime.agentscope;

import com.haizhuo.brain.runtime.agentscope.context.RuntimeContextFactory;
import com.haizhuo.brain.runtime.agentscope.context.RunScopedDelegationBudget;
import com.haizhuo.brain.runtime.agentscope.event.AgentScopeEventTranslator;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessRuntimeTemplate;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessTemplateCache;
import com.haizhuo.brain.observability.AgentExecutionObserver;
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
import io.agentscope.core.state.AgentStateStore;
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
    private final AgentExecutionObserver observer;
    private final AgentStateStore stateStore;

    public AgentScopeRuntime(HarnessTemplateCache templateCache, RuntimeContextFactory contextFactory,
                             AgentScopeEventTranslator translator) {
        this(templateCache, contextFactory, translator, AgentExecutionObserver.noop());
    }

    public AgentScopeRuntime(HarnessTemplateCache templateCache, RuntimeContextFactory contextFactory,
                             AgentScopeEventTranslator translator, AgentExecutionObserver observer) {
        this(templateCache, contextFactory, translator, observer, null);
    }

    public AgentScopeRuntime(HarnessTemplateCache templateCache, RuntimeContextFactory contextFactory,
                             AgentScopeEventTranslator translator, AgentExecutionObserver observer,
                             AgentStateStore stateStore) {
        this.templateCache = Objects.requireNonNull(templateCache);
        this.contextFactory = Objects.requireNonNull(contextFactory);
        this.translator = Objects.requireNonNull(translator);
        this.observer = Objects.requireNonNull(observer);
        this.stateStore = stateStore;
    }

    @Override
    public Flux<BrainAgentEvent> execute(AgentExecutionRequest request) {
        return Flux.defer(() -> {
                    safe(() -> observer.onStarted(request));
                    if (request.binding().stateRequired()) {
                        if (stateStore == null || !stateStore.exists(Long.toString(request.userId().value()),
                                request.binding().harnessSessionKey())) {
                            AgentRunFailedEvent failed = new AgentRunFailedEvent(request.runId(),
                                    "RUNTIME_STATE_MISSING：会话恢复状态缺失，请核查，禁止以空记忆继续。");
                            safe(() -> observer.onFailed(request, failed));
                            return Mono.just(failed);
                        }
                    }
                    RuntimeContext context = contextFactory.create(request);
                    boolean runScopedTemplate = request.definition().configuration().profile()
                            == com.haizhuo.brain.runtime.api.model.RuntimeProfile.TEAM_READONLY
                            && request.definition().configuration().team() != null
                            && !request.definition().configuration().team().allowedDelegations().isEmpty();
                    HarnessRuntimeTemplate template = runScopedTemplate
                            ? templateCache.buildForRun(request.definition())
                            : templateCache.getOrBuild(request.definition());
                    Flux<AgentEvent> events = stream(template, request.input(), context);
                    // 一旦已经翻译出挂起事件，同一条流末尾的完成事件
                    // 就不能再把 Run 翻回 SUCCEEDED（§34）。
                    AtomicBoolean suspended = new AtomicBoolean();
                    Flux<BrainAgentEvent> translated = Flux.using(
                            () -> translator.forExecution(context.getSessionId(), request.attemptId(),
                                    request.fenceToken()),
                            executionTranslator -> events.concatMap(
                                    event -> executionTranslator.translate(request.runId(), event))
                            .doOnNext(event -> {
                                if (event instanceof AgentToolSuspendedEvent) {
                                    suspended.set(true);
                                }
                            })
                            .filter(event -> !(suspended.get() && event instanceof AgentRunCompletedEvent))
                            .doOnNext(event -> safe(() -> observe(request, event))),
                            AgentScopeEventTranslator::close);
                    if (!runScopedTemplate) return translated;
                    return translated.doFinally(signal -> {
                        RunScopedDelegationBudget budget = context.get(RunScopedDelegationBudget.class);
                        if (budget != null) budget.closeUnstarted();
                        template.close();
                    });
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
                    AgentRunFailedEvent failed = new AgentRunFailedEvent(request.runId(),
                            "AgentScope执行异常类型：" + root.getClass().getSimpleName());
                    safe(() -> observer.onFailed(request, failed));
                    return Mono.just(failed);
                });
    }

    private void observe(AgentExecutionRequest request, BrainAgentEvent event) {
        if (event instanceof AgentRunCompletedEvent completed) {
            observer.onCompleted(request, completed);
        } else if (event instanceof AgentRunFailedEvent failed) {
            observer.onFailed(request, failed);
        } else if (event instanceof AgentToolSuspendedEvent suspended) {
            observer.onSuspended(request, suspended);
        }
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 观测端口不能改变 Runtime 的业务事件流。
        }
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
