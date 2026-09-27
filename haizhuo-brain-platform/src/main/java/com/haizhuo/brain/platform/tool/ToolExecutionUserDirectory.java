package com.haizhuo.brain.platform.tool;

/** 执行期校验所需的最小用户状态端口（规格 §37 第 4 步）。 */
public interface ToolExecutionUserDirectory {
    boolean isUserActive(long userId);
}
