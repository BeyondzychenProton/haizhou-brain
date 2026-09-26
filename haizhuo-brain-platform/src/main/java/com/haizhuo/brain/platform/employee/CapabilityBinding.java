package com.haizhuo.brain.platform.employee;

/** A specific Skill, Tool, MCP or Knowledge revision permitted by a definition. */
public record CapabilityBinding(long definitionVersionId, CapabilityType type,
                                String referenceId, String revision) {
    public enum CapabilityType { SKILL, TOOL, MCP, KNOWLEDGE }
}
