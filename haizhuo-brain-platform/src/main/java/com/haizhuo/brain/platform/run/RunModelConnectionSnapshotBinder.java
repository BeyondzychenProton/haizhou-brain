package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.platform.employee.ModelConnectionBindingStore;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeEmployeeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeModelConnectionRef;
import java.util.List;
import java.util.Objects;

/**
 * 使用精确的不可变连接引用重建运行时快照，并冻结当前 Run 的执行者身份。
 * 读取任何绑定前，先将请求身份与已持久化的 Run 目标进行核对。
 */
public final class RunModelConnectionSnapshotBinder {
    private final SessionRunStore sessions;
    private final ModelConnectionBindingStore bindings;

    public RunModelConnectionSnapshotBinder(SessionRunStore sessions, ModelConnectionBindingStore bindings) {
        this.sessions = Objects.requireNonNull(sessions);
        this.bindings = Objects.requireNonNull(bindings);
    }

    public AgentExecutionRequest bind(AgentExecutionRequest request) {
        AgentRun run = sessions.findRun(request.runId(), request.userId())
                .orElseThrow(ModelConnectionBindingUnavailableException::new);
        if (!run.sessionId().equals(request.platformSessionId()))
            throw new ModelConnectionBindingUnavailableException();

        RunExecutionTarget target = sessions.findExecutionTarget(request.runId(), request.userId())
                .orElseGet(() -> RunExecutionTarget.coordinator(run.employeeId(), run.definitionVersionId()));
        if (target.mode() != RunExecutionMode.DIRECT
                || target.definitionVersionId() != request.definition().definitionVersionId()
                || (request.binding().roleId() != null && !target.roleId().equals(request.binding().roleId()))) {
            throw new ModelConnectionBindingUnavailableException();
        }

        RuntimeDefinitionSnapshot definition = bindDefinition(request.runId(), target.roleId(),
                request.definition());
        return new AgentExecutionRequest(request.tenantId(), request.userId(), request.platformSessionId(),
                request.runId(), request.traceId(), request.attemptId(), request.fenceToken(), definition,
                request.constraints(), request.binding(), request.input());
    }

    private RuntimeDefinitionSnapshot bindDefinition(com.haizhuo.brain.kernel.identity.RunId runId,
                                                     String executorRoleId,
                                                     RuntimeDefinitionSnapshot definition) {
        RuntimeModelConnectionRef reference = bindings.findDefinitionVersionBinding(
                        definition.definitionVersionId(), definition.modelProvider())
                .orElseThrow(ModelConnectionBindingUnavailableException::new);
        if (definition.modelConnectionRef() != null && !definition.modelConnectionRef().equals(reference))
            throw new ModelConnectionBindingUnavailableException();
        bindings.freezeRunBinding(runId, executorRoleId, definition.definitionVersionId(),
                definition.modelProvider(), reference);

        RuntimeEmployeeConfiguration configuration = definition.configuration();
        List<RuntimeEmployeeConfiguration.FixedMember> members = configuration.members().stream()
                .map(member -> {
                    if (member.definition() == null)
                        throw new ModelConnectionBindingUnavailableException();
                    RuntimeDefinitionSnapshot memberDefinition = bindDefinition(runId, member.roleId(),
                            member.definition());
                    return new RuntimeEmployeeConfiguration.FixedMember(member.roleId(), member.employeeId(),
                            member.definitionVersionId(), member.steps(), memberDefinition);
                }).toList();
        RuntimeEmployeeConfiguration boundConfiguration = new RuntimeEmployeeConfiguration(
                configuration.schemaVersion(), configuration.profile(), configuration.policy(),
                configuration.team(), members);
        return new RuntimeDefinitionSnapshot(definition.definitionVersionId(), definition.employeeName(),
                definition.instructions(), definition.modelProvider(), definition.modelName(),
                definition.maxIterations(), definition.definitionBundleHash(), definition.workspaceProjectionKey(),
                definition.workspaceContentHash(), definition.workspaceManifestJson(), definition.toolCatalog(),
                boundConfiguration, definition.workspaceFiles(), reference);
    }
}
