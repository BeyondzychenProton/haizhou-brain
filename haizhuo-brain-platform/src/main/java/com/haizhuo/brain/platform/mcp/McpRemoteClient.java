package com.haizhuo.brain.platform.mcp;

import java.util.List;
import java.util.Map;

/** 协议适配接口，不得跨终端用户身份共享调用状态。 */
public interface McpRemoteClient {
    List<McpToolDescriptor> listTools(McpConnection connection, String bearerToken);
    McpCallResult call(McpConnection connection, String bearerToken, String remoteName,
                       Map<String, Object> arguments, String operationKey);

    record McpCallResult(boolean error, String content) { }
}
