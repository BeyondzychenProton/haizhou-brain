-- P3 方向 4：渠道账号补上"会话粒度"这一框架级配置。
-- AgentScope 的默认隔离粒度是整渠道共用一个会话，若直接沿用，同一渠道下的不同
-- 外部用户会读到彼此的历史，因此把它变成显式可配的持久化字段，默认取更严格的 PER_PEER。
-- 取值与框架 DmScope 一一对应：MAIN / PER_PEER / PER_CHANNEL_PEER / PER_ACCOUNT_CHANNEL_PEER。
-- 存量行由 DEFAULT 回填，语义为"收紧"，不会放宽既有隔离。

ALTER TABLE platform_channel_account
    ADD COLUMN dm_scope VARCHAR(32) NOT NULL DEFAULT 'PER_PEER';
