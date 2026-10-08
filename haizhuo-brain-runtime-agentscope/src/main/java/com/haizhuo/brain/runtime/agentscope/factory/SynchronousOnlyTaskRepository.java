package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.Collection;
import java.util.List;

/** Native async task storage is deliberately unavailable for the synchronous expert profile. */
final class SynchronousOnlyTaskRepository implements TaskRepository {
    @Override public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) { return null; }
    @Override public BackgroundTask putTask(RuntimeContext rc, String taskId, String subAgentId,
                                             String sessionId, TaskRunSpec spec) {
        throw new SecurityException("background expert execution is disabled for this profile");
    }
    @Override public Collection<BackgroundTask> listTasks(RuntimeContext rc, String sessionId, TaskStatus filter) {
        return List.of();
    }
    @Override public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) { return false; }
    @Override public List<io.agentscope.harness.agent.subagent.task.TaskDelivery> findPendingDeliveries(
            RuntimeContext rc, String sessionId) { return List.of(); }
    @Override public void markDelivered(RuntimeContext rc, String sessionId, String taskId) { }
    @Override public boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) { return false; }
    @Override public void shutdown() { }
}
