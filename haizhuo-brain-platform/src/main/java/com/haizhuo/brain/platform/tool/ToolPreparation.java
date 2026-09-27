package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import java.util.Objects;

/**
 * 固定授权流水线的结果（规格 §36.1/§37）。三态：
 * ALLOWED 继续执行；APPROVAL_REQUIRED 把执行停靠下来等待人工决定（§39）；
 * DENIED 产出面向模型的安全文案，绝不泄漏内部授权规则（§37）。
 */
public record ToolPreparation(Outcome outcome, String denialCode, String safeMessage,
                              CapabilityCatalogEntry capability) {
    public enum Outcome { ALLOWED, DENIED, APPROVAL_REQUIRED }

    public ToolPreparation {
        Objects.requireNonNull(outcome);
        if (outcome == Outcome.DENIED) {
            Objects.requireNonNull(denialCode);
            Objects.requireNonNull(safeMessage);
        } else {
            Objects.requireNonNull(capability);
        }
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOWED;
    }

    public static ToolPreparation allowed(CapabilityCatalogEntry capability) {
        return new ToolPreparation(Outcome.ALLOWED, null, null, capability);
    }

    public static ToolPreparation approvalRequired(CapabilityCatalogEntry capability) {
        return new ToolPreparation(Outcome.APPROVAL_REQUIRED, null, null, capability);
    }

    public static ToolPreparation denied(String code, String safeMessage) {
        return new ToolPreparation(Outcome.DENIED, code, safeMessage, null);
    }
}
