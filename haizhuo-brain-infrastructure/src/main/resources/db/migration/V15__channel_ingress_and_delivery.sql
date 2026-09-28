-- P3：首个渠道接入的持久化底座。
-- 三件事各占一张表，互不混淆：
--   1. 收件箱 (binding, provider_event) 唯一约束负责"同一条外部消息重投只建一个 Run"；
--   2. 身份与会话映射把外部标识稳定映射到平台用户的 Session；
--   3. 投递表的 idempotency_key 负责"发送前落库、崩溃后不盲目重发"。
-- 会话与 Run 本身仍复用既有 platform_agent_session / platform_agent_run，
-- 单会话唯一活动 Run 的排队也仍由既有 createRun 保证，渠道侧不重复实现。

CREATE TABLE platform_channel_account (
    binding_id VARCHAR(64) NOT NULL,
    tenant_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_account_key VARCHAR(128) NOT NULL,
    credential_ref VARCHAR(256) NOT NULL,
    default_employee_id BIGINT NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (binding_id),
    UNIQUE KEY uk_platform_channel_account_external (provider, external_account_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 身份关联只能由经过校验的绑定流程写入；REVOKED 保留行以便审计与解绑后抑制旧投递。
CREATE TABLE platform_channel_identity (
    binding_id VARCHAR(64) NOT NULL,
    external_user_id VARCHAR(128) NOT NULL,
    user_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    linked_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (binding_id, external_user_id),
    KEY idx_platform_channel_identity_user (user_id),
    CONSTRAINT fk_platform_channel_identity_account FOREIGN KEY (binding_id)
        REFERENCES platform_channel_account (binding_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 外部会话到平台 Session 的稳定映射；一个平台 Session 只属于一个外部会话，
-- 因此 Web 会话与 IM 会话天然隔离。
CREATE TABLE platform_channel_conversation (
    binding_id VARCHAR(64) NOT NULL,
    external_conversation_id VARCHAR(191) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (binding_id, external_conversation_id),
    UNIQUE KEY uk_platform_channel_conversation_session (session_id),
    CONSTRAINT fk_platform_channel_conversation_account FOREIGN KEY (binding_id)
        REFERENCES platform_channel_account (binding_id),
    CONSTRAINT fk_platform_channel_conversation_session FOREIGN KEY (session_id)
        REFERENCES platform_agent_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 入站收件箱：受理与去重的事实来源。reply_target 允许短期密钥，按契约要求需加密或带过期时间存储。
CREATE TABLE platform_channel_inbox (
    binding_id VARCHAR(64) NOT NULL,
    provider_event_id VARCHAR(191) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_conversation_id VARCHAR(191) NOT NULL,
    external_user_id VARCHAR(128) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(36) NULL,
    run_id VARCHAR(36) NULL,
    reply_target VARCHAR(512) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    accepted_at DATETIME(3) NOT NULL,
    PRIMARY KEY (binding_id, provider_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 出站投递：先落库再发送。state 区分已投递、可重试、永久失败与结果不确定，
-- 最后一种绝不允许自动重发，只能由人工核查。
CREATE TABLE platform_channel_delivery (
    delivery_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    binding_id VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    reply_target VARCHAR(512) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    idempotency_key VARCHAR(191) NOT NULL,
    state VARCHAR(24) NOT NULL,
    external_message_id VARCHAR(191) NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(256) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (delivery_id),
    UNIQUE KEY uk_platform_channel_delivery_idempotency (idempotency_key),
    KEY idx_platform_channel_delivery_state (state, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
