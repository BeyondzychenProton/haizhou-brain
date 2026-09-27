package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;

/**
 * 白名单执行器注册表（规格 §42.1）。数据库中的值绝不能变成类加载、脚本执行
 * 或任意网络入口——只有预先注册的键才能被解析。
 */
public interface CapabilityExecutorRegistry {
    boolean supports(String implementationKey, String capabilityCode);

    CapabilityExecutor resolve(CapabilityCatalogEntry capability);
}
