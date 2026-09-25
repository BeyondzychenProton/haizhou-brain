package com.haizhuo.brain.security.application;

import com.haizhuo.brain.security.domain.PolicyDecision;
import com.haizhuo.brain.security.domain.subject.Subject;
import com.haizhuo.brain.security.domain.resource.Resource;
import com.haizhuo.brain.security.domain.policy.Action;

public interface AuthorizationService {
    PolicyDecision decide(Subject subject, Resource resource, Action action);
}
