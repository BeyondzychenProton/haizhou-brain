package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import java.util.Map;

/**
 * P0 默认实现：资源就是该能力的业务动作本身，且所有已授权用户都允许。
 * 细粒度资源语法（例如由 {@code roomId} 推导出 {@code meeting-room:A201}）属于已记录的后续项，
 * 必须实现在这里，以保证 §37 的固定流水线顺序不被破坏。
 */
public class DefaultToolResourcePolicy implements ToolResourcePolicy {

    @Override
    public String deriveResource(CapabilityCatalogEntry capability, Map<String, Object> arguments) {
        return capability.businessAction();
    }

    @Override
    public boolean allows(UserId userId, CapabilityCatalogEntry capability, String resource,
                          Map<String, Object> arguments) {
        return true;
    }
}
