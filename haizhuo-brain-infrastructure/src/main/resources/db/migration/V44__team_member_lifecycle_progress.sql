-- 只记录 AgentScope 成员事件中的有序生命周期状态；BOUND 对普通用户仍显示为 UNKNOWN。
ALTER TABLE platform_run_team_member_binding
    ADD COLUMN last_transition_ordinal BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_transition_phase TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN last_transition_at DATETIME(3) NULL;
