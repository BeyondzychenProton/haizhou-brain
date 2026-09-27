package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import java.util.Map;

/**
 * 白名单内的平台执行器，负责一个实现族（规格 §42）。它取代原先运行时侧的
 * RuntimeToolProvider：企业副作用一律在平台侧执行，绝不在 Harness 内执行。
 */
public interface CapabilityExecutor {

    /** 与能力修订上的 implementationKey 匹配的稳定实现键。 */
    String implementationKey();

    boolean supports(CapabilityCatalogEntry capability);

    ToolExecutionResult execute(CapabilityExecutionContext context, Map<String, Object> arguments);
}
