package com.haizhuo.brain.platform.session;

/** Safe Session role listing; native state keys and internal collaboration handles are deliberately absent. */
public record SessionRoleOption(String roleId, String displayName, long employeeId,
                                Long definitionVersionId, boolean selectable) { }
