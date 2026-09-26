package com.haizhuo.brain.platform.channel;

import java.util.Optional;

public interface ChannelAccountDirectory {
    Optional<ChannelAccountBinding> findById(String bindingId);
}
