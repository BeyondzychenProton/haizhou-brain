package com.haizhuo.brain.platform.employee;

/** 定义所允许的一个具体 Skill / Tool / MCP / Knowledge 修订。 */
public record CapabilityBinding(long definitionVersionId, CapabilityType type,
                                String referenceId, String revision) {
    public enum CapabilityType { SKILL, TOOL, MCP, KNOWLEDGE }
}
