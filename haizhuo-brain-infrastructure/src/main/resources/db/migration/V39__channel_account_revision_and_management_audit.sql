-- 账号配置修订号用于条件更新，现有记录从修订号 1 开始。
ALTER TABLE platform_channel_account
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;

-- 管理审计与账号/身份命令在同一事务中提交。
-- 载荷只保留摘要和白名单变更摘要，不保存原始请求。
CREATE TABLE platform_channel_management_audit (
    audit_id BIGINT NOT NULL AUTO_INCREMENT,
    binding_id VARCHAR(64) NOT NULL,
    external_user_id VARCHAR(128) NULL,
    action_code VARCHAR(48) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    request_id VARCHAR(128) NULL,
    reason VARCHAR(500) NULL,
    client_source VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    safe_changes TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (audit_id),
    KEY idx_channel_management_audit_binding (binding_id, created_at, audit_id),
    KEY idx_channel_management_audit_actor (actor_user_id, created_at, audit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 超时重试只保留请求摘要和安全响应快照。
-- 此处绝不存储凭据材料或请求正文。
CREATE TABLE platform_channel_management_receipt (
    actor_user_id BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    action_code VARCHAR(48) NOT NULL,
    binding_id VARCHAR(64) NOT NULL,
    external_user_id VARCHAR(128) NULL,
    payload_hash CHAR(64) NOT NULL,
    result_json LONGTEXT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (actor_user_id, request_id),
    KEY idx_channel_management_receipt_binding (binding_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
