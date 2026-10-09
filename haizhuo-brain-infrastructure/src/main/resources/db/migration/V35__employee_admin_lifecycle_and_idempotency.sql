-- Administrative employee pages require a stable creation order. Existing employees
-- share the migration time; the id tie-breaker keeps the keyset deterministic.
ALTER TABLE digital_employee
    ADD COLUMN created_at DATETIME(3) NULL;

UPDATE digital_employee
SET created_at = CURRENT_TIMESTAMP(3)
WHERE created_at IS NULL;

ALTER TABLE digital_employee
    MODIFY COLUMN created_at DATETIME(3) NOT NULL;

CREATE INDEX idx_digital_employee_tenant_created
    ON digital_employee (tenant_id, created_at, id);

-- The legacy employee table uses application-assigned IDs. Serialize allocations
-- in this row instead of relying on MAX(id) during concurrent creates.
CREATE TABLE platform_id_allocator (
    scope_key VARCHAR(64) NOT NULL,
    next_id BIGINT NOT NULL,
    PRIMARY KEY (scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO platform_id_allocator (scope_key, next_id)
SELECT 'digital_employee', COALESCE(MAX(id), 0) + 1
FROM digital_employee;

-- Idempotency receipts commit atomically with the administrative mutation and
-- keep the response needed by clients that retry after an uncertain connection.
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
