package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.UserId;

/**
 * Atomic inbox acceptance, deduplication, conversation mapping and single-session Run queuing.
 * Implementations must commit inbox, Session and pending Run together; enforce unique
 * (binding,event) and conversation keys and one active Run per Session in the database.
 * Reply targets containing short-lived secrets must be encrypted or stored with an expiry.
 */
public interface ChannelTurnStore {
    ChannelAcceptance accept(VerifiedChannelMessage message, ChannelAccountBinding binding, UserId userId);
}
