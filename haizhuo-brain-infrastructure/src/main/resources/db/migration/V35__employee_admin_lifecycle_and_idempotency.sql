-- 管理员员工列表需要稳定的创建顺序。已有员工使用相同迁移时间，
-- 通过 ID 作为次级排序键，保证 keyset 分页结果确定。
ALTER TABLE digital_employee
    ADD COLUMN created_at DATETIME(3) NULL;

UPDATE digital_employee
SET created_at = CURRENT_TIMESTAMP(3)
WHERE created_at IS NULL;

ALTER TABLE digital_employee
    MODIFY COLUMN created_at DATETIME(3) NOT NULL;

CREATE INDEX idx_digital_employee_tenant_created
    ON digital_employee (tenant_id, created_at, id);

-- 旧员工表的 ID 由应用分配。并发创建时通过此行串行分配，
-- 不依赖 MAX(id) 推导下一个 ID。
CREATE TABLE platform_id_allocator (
    scope_key VARCHAR(64) NOT NULL,
    next_id BIGINT NOT NULL,
    PRIMARY KEY (scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO platform_id_allocator (scope_key, next_id)
SELECT 'digital_employee', COALESCE(MAX(id), 0) + 1
FROM digital_employee;

-- 幂等回执与管理员变更原子提交，
-- 并保存客户端在连接结果不确定时重试所需的响应。
CREATE TABLE employee_admin_command_receipt (
    actor_user_id BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    employee_id BIGINT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json LONGTEXT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (actor_user_id, request_id),
    KEY idx_employee_admin_receipt_employee (employee_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
