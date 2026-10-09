package com.haizhuo.brain.platform.employee;

import java.util.function.Supplier;

/** 执行管理员命令，并将响应与业务变更原子写入。 */
public interface EmployeeAdminCommandExecutor {
    <T> T execute(Command command, Class<T> responseType, Supplier<T> action);

    record Command(long actorId, String action, Long employeeId, String requestId, String requestHash) {}
}
