package com.haizhuo.brain.security.port;

public interface ApprovalRepository {
    boolean isApproved(String taskId, String action);
}
