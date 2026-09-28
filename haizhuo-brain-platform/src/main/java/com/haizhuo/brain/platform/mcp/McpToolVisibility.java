package com.haizhuo.brain.platform.mcp;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;

/** 每个 Run 独立预检；失败必须中止创建，不能伪装成空工具列表。 */
public interface McpToolVisibility {
    boolean visible(UserId user, CapabilityCatalogEntry entry);
}
