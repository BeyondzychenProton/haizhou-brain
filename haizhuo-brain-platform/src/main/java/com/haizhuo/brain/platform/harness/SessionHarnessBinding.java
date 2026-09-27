package com.haizhuo.brain.platform.harness;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;
import java.util.Objects;

/**
 * 平台侧的属主记录：把平台会话 + 定义版本关联到 Harness 数据面（规格 §6.4）。
 * 键为不可猜测的 UUID（§16.2），绝不是路径拼接。两个数据面彼此独立（I-02/I-03）：
 * harnessSessionKey 寻址 AgentState；workspaceRuntimeKey 寻址运行时文件。
 * 新的 definitionVersionId 一定会拿到全新的键——版本切换绝不读取旧的 Harness 状态（§16.3）。
 */
public record SessionHarnessBinding(long id, UserId userId, SessionId platformSessionId,
                                    long employeeId, long definitionVersionId, int generation,
                                    String harnessSessionKey, String workspaceRuntimeKey,
                                    long bridgeSnapshotId, Instant createdAt) {
    public SessionHarnessBinding {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(platformSessionId);
        if (employeeId <= 0 || definitionVersionId <= 0) {
            throw new IllegalArgumentException("employee and definition version are required");
        }
        if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
        Objects.requireNonNull(harnessSessionKey);
        Objects.requireNonNull(workspaceRuntimeKey);
        if (bridgeSnapshotId <= 0) throw new IllegalArgumentException("bridgeSnapshotId must be positive");
        Objects.requireNonNull(createdAt);
    }
}
