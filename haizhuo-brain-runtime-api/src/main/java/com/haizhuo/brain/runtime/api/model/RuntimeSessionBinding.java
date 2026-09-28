package com.haizhuo.brain.runtime.api.model;

import java.util.Objects;

/**
 * 平台下发的映射：平台 Session + 定义版本 → AgentScope 状态槽（规格 §7.4）。
 * harnessSessionKey 寻址 AgentState；workspaceRuntimeKey 寻址工作区运行时文件，
 * 二者是两个互相独立的数据面（I-02/I-03）。bridgeContext 由平台预先构建并限长，
 * 运行时不会查询平台库。
 */
public record RuntimeSessionBinding(String harnessSessionKey, String workspaceRuntimeKey,
                                    String bridgeSnapshotHash, String bridgeContext) {
    public RuntimeSessionBinding {
        Objects.requireNonNull(harnessSessionKey);
        Objects.requireNonNull(workspaceRuntimeKey);
    }
}
