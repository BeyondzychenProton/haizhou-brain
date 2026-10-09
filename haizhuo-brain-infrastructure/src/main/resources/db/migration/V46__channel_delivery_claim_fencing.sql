-- FE-05-S3: fence every sender result to the claim that initiated that attempt.
ALTER TABLE platform_channel_delivery
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN claim_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN claim_token CHAR(36) NULL;

-- Existing aggregate attempts have no per-attempt evidence. Keep their count, but seed the
-- next claim generation from it so future generations remain monotonic for each delivery.
UPDATE platform_channel_delivery SET claim_generation=attempts;

-- A migration cannot prove that a pre-S3 sender stopped or that its request was not accepted.
-- Retire those leases as uncertain and require evidence-based handling before any future retry.
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
