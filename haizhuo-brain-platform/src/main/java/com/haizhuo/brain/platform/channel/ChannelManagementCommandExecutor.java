package com.haizhuo.brain.platform.channel;

import java.util.function.Supplier;

/** 原子执行渠道管理命令，并同时保存审计记录和幂等回执。 */
public interface ChannelManagementCommandExecutor {

    ChannelManagementCommandExecutor DIRECT = new ChannelManagementCommandExecutor() {
        @Override
        public <T> T execute(Command command, Class<T> responseType, Supplier<T> action) {
            return action.get();
        }
    };

    <T> T execute(Command command, Class<T> responseType, Supplier<T> action);

    /** payloadCanonical 只计算摘要、不持久化；safeChanges 仅包含获准写入审计的字段。 */
    record Command(long actorUserId, String action, String bindingId, String externalUserId,
                   String requestId, String reason, String clientSource,
                   String payloadCanonical, String safeChanges) {
    }
}
