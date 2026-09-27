CREATE TABLE platform_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    mobile_normalized VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL,
    auth_version BIGINT NOT NULL DEFAULT 1,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_user_mobile (mobile_normalized),
    KEY idx_platform_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_user_role (
    user_id BIGINT NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    granted_by BIGINT NULL,
    granted_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, role_code),
    KEY idx_platform_user_role_code (role_code, user_id),
    CONSTRAINT fk_platform_user_role_user FOREIGN KEY (user_id) REFERENCES platform_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_activation_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    selector VARCHAR(64) NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    used_at DATETIME(3) NULL,
    revoked_at DATETIME(3) NULL,
    created_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_activation_selector (selector),
    KEY idx_platform_activation_user_active (user_id, used_at, revoked_at, expires_at),
    CONSTRAINT fk_platform_activation_user FOREIGN KEY (user_id) REFERENCES platform_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_user_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NULL,
    target_user_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    previous_state TEXT NULL,
    new_state TEXT NULL,
    reason VARCHAR(500) NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_platform_user_audit_target_time (target_user_id, occurred_at),
    KEY idx_platform_user_audit_actor_time (actor_user_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_authentication_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL,
    subject_digest CHAR(64) NOT NULL,
    source_digest CHAR(64) NOT NULL,
    failure_category VARCHAR(64) NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_platform_auth_audit_subject_time (subject_digest, occurred_at),
    KEY idx_platform_auth_audit_source_time (source_digest, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A permanent single-row lock serializes first-admin and last-admin protection decisions.
CREATE TABLE platform_administration_lock (
    lock_key VARCHAR(32) NOT NULL,
    PRIMARY KEY (lock_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO platform_administration_lock (lock_key) VALUES ('ADMINISTRATION');
