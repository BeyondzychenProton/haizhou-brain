package com.haizhuo.brain.runtime.agentscope.team;

import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Model-visible root entry into the Run-scoped AgentScope native Team execution. */
public final class RunReadonlyTeamTool {
    private final NativeTeamExecutionGateway gateway;
    private final RuntimeDefinitionSnapshot frozenOwner;

    public RunReadonlyTeamTool(NativeTeamExecutionGateway gateway, RuntimeDefinitionSnapshot frozenOwner) {
        this.gateway = Objects.requireNonNull(gateway);
        this.frozenOwner = Objects.requireNonNull(frozenOwner);
    }

    @Tool(name = "run_readonly_team", description = "Run the frozen read-only expert team for a bounded objective."
            + " Returns only completed task results. Do not include credentials or request external actions.")
    public Mono<String> run(
            @ToolParam(name = "objective", description = "The bounded read-only objective for this Team", required = true)
            String objective,
            RuntimeContext runtimeContext) {
        return Mono.fromCallable(() -> gateway.execute(frozenOwner, objective, runtimeContext))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ignored -> Mono.just("团队协作未能安全完成；请以 Run 的平台状态和核查提示为准。"));
    }
}
