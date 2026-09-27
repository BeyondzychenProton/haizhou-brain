package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.kernel.identity.UserId;

/**
 * 解析执行期凭据（规格 §40）。解析出的值只在进程内、单次调用期间存在，
 * 绝不能进入事件、提示词、状态或日志（I-08）。
 */
public interface CredentialResolver {
    ResolvedCredential resolve(UserId userId, long capabilityRevisionId);
}
