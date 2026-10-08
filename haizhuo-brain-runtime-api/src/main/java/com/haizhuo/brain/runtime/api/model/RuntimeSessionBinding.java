package com.haizhuo.brain.runtime.api.model;

import java.util.Objects;

/**
 * 每次执行的会话寻址参数，不再为新会话持久化映射。
 * direct 使用业务 SessionId，bridge 字段为空；旧字段仅用于迁移前状态槽的兼容。
 * workspaceRuntimeKey 属于独立文件面，不能当作 AgentState 的镜像。
 */
public record RuntimeSessionBinding(String harnessSessionKey, String workspaceRuntimeKey,
                                    String bridgeSnapshotHash, String bridgeContext, boolean stateRequired,
                                    IdentityMode identityMode, String roleId) {
    public static final String COORDINATOR_ROLE = "coordinator";

    /** 存量 Runtime 映射；新会话使用 direct，不再创建持久映射。 */
    public RuntimeSessionBinding(String harnessSessionKey, String workspaceRuntimeKey,
                                 String bridgeSnapshotHash, String bridgeContext) {
        this(harnessSessionKey, workspaceRuntimeKey, bridgeSnapshotHash, bridgeContext, false,
                bridgeSnapshotHash == null ? IdentityMode.DIRECT : IdentityMode.LEGACY_BRIDGE,
                bridgeSnapshotHash == null ? COORDINATOR_ROLE : null);
    }

    /** Source-compatible constructor; bridge-backed callers are explicitly treated as legacy. */
    public RuntimeSessionBinding(String harnessSessionKey, String workspaceRuntimeKey,
                                 String bridgeSnapshotHash, String bridgeContext, boolean stateRequired) {
        this(harnessSessionKey, workspaceRuntimeKey, bridgeSnapshotHash, bridgeContext, stateRequired,
                bridgeSnapshotHash == null ? IdentityMode.DIRECT : IdentityMode.LEGACY_BRIDGE,
                bridgeSnapshotHash == null ? COORDINATOR_ROLE : null);
    }

    public static RuntimeSessionBinding direct(String sessionId, boolean stateRequired) {
        return new RuntimeSessionBinding(sessionId, "ws_" + sessionId, null, null, stateRequired,
                IdentityMode.DIRECT, COORDINATOR_ROLE);
    }

    /** Server persisted role-slot keys keep expert state and files isolated from the coordinator and other roles. */
    public static RuntimeSessionBinding roleSlot(String roleId, String harnessSessionKey,
                                                 String workspaceRuntimeKey, boolean stateRequired) {
        return new RuntimeSessionBinding(harnessSessionKey, workspaceRuntimeKey, null, null, stateRequired,
                IdentityMode.ROLE_SLOT, roleId);
    }

    public RuntimeSessionBinding {
        Objects.requireNonNull(harnessSessionKey);
        Objects.requireNonNull(workspaceRuntimeKey);
        Objects.requireNonNull(identityMode);
        switch (identityMode) {
            case DIRECT -> {
                if (bridgeSnapshotHash != null || !COORDINATOR_ROLE.equals(roleId))
                    throw new IllegalArgumentException("Direct identity must use the coordinator role without a bridge");
            }
            case ROLE_SLOT -> {
                if (bridgeSnapshotHash != null || roleId == null || roleId.isBlank()
                        || COORDINATOR_ROLE.equals(roleId))
                    throw new IllegalArgumentException("Role-slot identity requires a non-coordinator role without a bridge");
            }
            case LEGACY_BRIDGE -> {
                if (bridgeSnapshotHash == null || bridgeSnapshotHash.isBlank() || roleId != null)
                    throw new IllegalArgumentException("Legacy bridge identity requires its frozen bridge hash");
            }
        }
    }

    public enum IdentityMode { DIRECT, ROLE_SLOT, LEGACY_BRIDGE }
}
