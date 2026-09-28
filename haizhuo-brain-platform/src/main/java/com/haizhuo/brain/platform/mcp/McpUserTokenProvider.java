package com.haizhuo.brain.platform.mcp;

import com.haizhuo.brain.kernel.identity.UserId;

/** 获取短时效、绑定目标服务的终端用户 Token；不得持久化或记录日志。 */
public interface McpUserTokenProvider {
    String tokenFor(UserId userId, McpConnection connection);
}
