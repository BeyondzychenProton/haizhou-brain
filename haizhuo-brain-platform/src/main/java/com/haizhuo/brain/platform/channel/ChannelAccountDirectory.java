package com.haizhuo.brain.platform.channel;

import java.util.List;
import java.util.Optional;

public interface ChannelAccountDirectory {

    Optional<ChannelAccountBinding> findById(String bindingId);

    /**
     * 全量绑定（含已禁用），供装配层重建渠道注册表使用。
     * 入站链路仍只走 {@link #findById(String)}，避免把管理面读放大到消息路径上。
     */
    default List<ChannelAccountBinding> findAll() {
        return List.of();
    }
}
