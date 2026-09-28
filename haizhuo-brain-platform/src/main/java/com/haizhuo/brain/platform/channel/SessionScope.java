package com.haizhuo.brain.platform.channel;

/**
 * 渠道会话的隔离粒度，取值与 AgentScope 的 {@code DmScope} 一一对应。
 *
 * <p>平台层不依赖框架，因此在这里独立声明，由装配层完成映射，避免框架类型渗进领域模型。</p>
 *
 * <p>默认取 {@link #PER_PEER}：框架的默认粒度是"整个渠道共用一个会话"，直接沿用会让同一渠道下
 * 的不同外部用户读到彼此的历史，因此这里显式收紧，并要求管理侧可配。</p>
 */
public enum SessionScope {

    /** 整个渠道共用一个会话；仅在同渠道只有一个对端时安全。 */
    MAIN,

    /** 每个外部对端一个会话。 */
    PER_PEER,

    /** 按渠道与对端共同隔离。 */
    PER_CHANNEL_PEER,

    /** 按渠道账号、渠道与对端共同隔离。 */
    PER_ACCOUNT_CHANNEL_PEER;

    public static SessionScope defaultScope() {
        return PER_PEER;
    }
}
