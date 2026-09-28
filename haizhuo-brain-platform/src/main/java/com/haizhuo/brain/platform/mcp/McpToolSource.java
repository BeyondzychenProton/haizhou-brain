package com.haizhuo.brain.platform.mcp;

/** 与能力修订版本绑定的已批准工具来源。 */
public record McpToolSource(long capabilityRevisionId, long connectionId, long connectionRevision,
                            String remoteName, java.util.Map<String, Object> outputSchema,
                            boolean readOnly) { }
