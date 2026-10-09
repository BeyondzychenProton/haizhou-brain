package com.haizhuo.brain.platform.channel;

/** 账号的乐观锁修订号与持久化记录不一致时抛出。 */
public final class ChannelAccountChangedException extends IllegalStateException {
    public ChannelAccountChangedException() {
        super("CHANNEL_ACCOUNT_CHANGED");
    }
}
