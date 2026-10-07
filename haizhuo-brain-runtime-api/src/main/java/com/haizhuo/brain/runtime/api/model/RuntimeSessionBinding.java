package com.haizhuo.brain.runtime.api.model;

import java.util.Objects;

/**
 * 每次执行的会话寻址参数，不再为新会话持久化映射。
 * direct 使用业务 SessionId，bridge 字段为空；旧字段仅用于迁移前状态槽的兼容。
 * workspaceRuntimeKey 属于独立文件面，不能当作 AgentState 的镜像。
 */
public record RuntimeSessionBinding(String harnessSessionKey, String workspaceRuntimeKey,
                                    String bridgeSnapshotHash, String bridgeContext, boolean stateRequired) {
    /** 存量 Runtime 映射；新会话使用 direct，不再创建持久映射。 */
    public RuntimeSessionBinding(String harnessSessionKey, String workspaceRuntimeKey,
                                 String bridgeSnapshotHash, String bridgeContext) {
        this(harnessSessionKey, workspaceRuntimeKey, bridgeSnapshotHash, bridgeContext, false);
    }

    public static RuntimeSessionBinding direct(String sessionId, boolean stateRequired) {
        return new RuntimeSessionBinding(sessionId, "ws_" + sessionId, null, null, stateRequired);
    }

    public RuntimeSessionBinding {
        Objects.requireNonNull(harnessSessionKey);
        Objects.requireNonNull(workspaceRuntimeKey);
    }
}
