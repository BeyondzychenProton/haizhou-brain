package com.haizhuo.brain.platform.channel;

import java.util.List;

/**
 * 渠道运行时的管理面，由装配层基于 AgentScope 的 {@code ChannelManager} 实现。
 *
 * <p>管理服务在账号绑定变化后调用它，让框架侧的渠道注册表与持久化配置保持一致：
 * 新增渠道被装载、无启用绑定的渠道被卸载、会话粒度等配置被热更新。</p>
 *
 * <p>未装配渠道运行时（测试或无渠道能力的环境）时用 {@link #NOOP} 占位，
 * 此时绑定数据仅落库，入站链路仍可正常受理。</p>
 */
public interface ChannelRuntimeAdmin {

    ChannelRuntimeAdmin NOOP = new ChannelRuntimeAdmin() {
        @Override
        public List<ChannelRuntimeStatus> channels() {
            return List.of();
        }

        @Override
        public void refreshAll() {
            // 未装配渠道运行时时不产生任何运行时副作用。
        }
    };

    /** 当前已装载的渠道及其生效配置。 */
    List<ChannelRuntimeStatus> channels();

    /** 依据持久化绑定重建渠道注册表：装载新增、卸载失活、热更新已变更的配置。 */
    void refreshAll();

    /** 渠道运行时快照；{@code bindingCount} 只统计启用中的绑定。 */
    record ChannelRuntimeStatus(String channelId, String defaultAgentId, String sessionScope,
                                int bindingCount, boolean started) {
    }
}
