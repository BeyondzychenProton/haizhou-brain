-- 新会话直接以业务 SessionId 寻址 AgentScope；存量会话继续用原 Binding。
-- 只存身份与关系，不搬运/复制 AgentState。不得在线合并多版本 Runtime 状态。
ALTER TABLE platform_agent_session ADD COLUMN definition_version_id BIGINT NULL;
ALTER TABLE platform_agent_session ADD COLUMN legacy_runtime BOOLEAN NOT NULL DEFAULT TRUE;

-- 历史 Run 已冻结版本，选最近一次 Run 作为此后固定版本。
-- 无 Run 的旧会话保留 NULL，首次执行以属主条件原子固定。
UPDATE platform_agent_session
SET definition_version_id = (
    SELECT run.definition_version_id FROM platform_agent_run run
    WHERE run.session_id = platform_agent_session.session_id
    ORDER BY run.created_at DESC, run.run_id DESC LIMIT 1
);

ALTER TABLE platform_agent_session
    ADD CONSTRAINT fk_platform_agent_session_definition
    FOREIGN KEY (definition_version_id) REFERENCES agent_definition_version (id);
