-- FE-05-S3：将发送器的每个结果限制在发起该次尝试的 claim 内。
ALTER TABLE platform_channel_delivery
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN claim_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN claim_token CHAR(36) NULL;

-- 现有聚合尝试数没有逐次尝试的证据。保留原计数，并以此初始化下一代 claim，
-- 确保每个 Delivery 后续的代数单调递增。
UPDATE platform_channel_delivery SET claim_generation=attempts;

-- 迁移无法证明 S3 之前的 sender 已停止，也无法证明其请求未被接收。
-- 将这些租约标记为结果不确定；后续重试前必须先依据证据核查。
UPDATE platform_channel_delivery
SET state='UNCERTAIN',
    last_error='SENDING_UPGRADE_UNCERTAIN',
    sending_expires_at=NULL,
    claim_token=NULL,
    revision=revision+1,
    updated_at=CURRENT_TIMESTAMP(3)
WHERE state='SENDING';

CREATE TABLE platform_channel_delivery_attempt (
    delivery_id VARCHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    claim_generation BIGINT NOT NULL,
    claim_token CHAR(36) NOT NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    status VARCHAR(24) NOT NULL,
    safe_error_code VARCHAR(32) NULL,
    PRIMARY KEY (delivery_id, attempt_no),
    UNIQUE KEY uk_platform_delivery_attempt_generation (delivery_id, claim_generation),
    CONSTRAINT fk_platform_delivery_attempt_delivery FOREIGN KEY (delivery_id)
        REFERENCES platform_channel_delivery (delivery_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
