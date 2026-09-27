package com.haizhuo.brain.platform.employee.runtime;

import com.haizhuo.brain.platform.employee.AgentDefinitionDraft;

/**
 * 在发布时把已校验的草稿编译成不可变的 HarnessDefinitionBundle（规格 §8.2）。
 * 返回的包 id=0；代理主键由持久化层分配。
 */
public interface HarnessDefinitionBundleCompiler {
    HarnessDefinitionBundle compile(long definitionVersionId, String employeeName, AgentDefinitionDraft draft);
}
