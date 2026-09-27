package com.haizhuo.brain.platform.harness;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.util.Optional;

/** Harness 绑定的持久化边界（规格 §16.1：先查后建，作用域唯一）。 */
public interface SessionHarnessBindingRepository {

    Optional<SessionHarnessBinding> findByScope(UserId userId, SessionId platformSessionId,
                                                long definitionVersionId, int generation);

    SessionHarnessBinding save(SessionHarnessBinding binding);
}
