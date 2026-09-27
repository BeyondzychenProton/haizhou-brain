package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import java.util.Map;

/**
 * Resource/Action 推导与策略钩子——固定授权流水线的第 6-7 步（规格 §37/§38）。
 * 资源由平台（而非模型）根据能力修订与参数推导。更严格的部署可以在这里接入，
 * 而不必改动流水线顺序。
 */
public interface ToolResourcePolicy {

    /** 从能力修订与工具参数推导受影响资源（§38）。 */
    String deriveResource(CapabilityCatalogEntry capability, Map<String, Object> arguments);

    /** 该用户是否可以对推导出的资源执行该能力的业务动作。 */
    boolean allows(UserId userId, CapabilityCatalogEntry capability, String resource,
                   Map<String, Object> arguments);
}
