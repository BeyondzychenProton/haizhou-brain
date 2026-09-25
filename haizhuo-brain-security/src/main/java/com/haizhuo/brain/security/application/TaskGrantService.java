package com.haizhuo.brain.security.application;

import com.haizhuo.brain.security.domain.PolicyDecision;

public interface TaskGrantService {
    PolicyDecision grant(String taskId, long userId);
}
