package com.haizhuo.brain.infrastructure.employee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.EmployeeAdminCommandExecutor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 将管理员幂等回执与对应员工变更写入同一事务。 */
@Repository
@Profile("!test")
public class JdbcEmployeeAdminCommandExecutor implements EmployeeAdminCommandExecutor {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcEmployeeAdminCommandExecutor(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
    }

    @Override
    public <T> T execute(Command command, Class<T> responseType, Supplier<T> action) {
        if (command.actorId() <= 0 || command.action() == null || command.action().isBlank()
                || command.requestId() == null || command.requestId().isBlank()
                || command.requestId().length() > 128 || command.requestHash() == null
                || !command.requestHash().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid idempotent employee command");
        return Objects.requireNonNull(tx.execute(status -> {
            jdbc.update("INSERT INTO employee_admin_command_receipt(actor_user_id,request_id,action_code,employee_id,request_hash,response_json,created_at) "
                            + "VALUES(?,?,?,?,?,NULL,?) ON DUPLICATE KEY UPDATE request_id=VALUES(request_id)",
                    command.actorId(), command.requestId(), command.action(), command.employeeId(), command.requestHash(),
                    Timestamp.from(Instant.now()));
            List<Receipt> receipts = jdbc.query("SELECT action_code,employee_id,request_hash,response_json FROM employee_admin_command_receipt "
                            + "WHERE actor_user_id=? AND request_id=? FOR UPDATE",
                    (rs, row) -> new Receipt(rs.getString("action_code"), rs.getObject("employee_id", Long.class),
                            rs.getString("request_hash"), rs.getString("response_json")),
                    command.actorId(), command.requestId());
            if (receipts.isEmpty()) throw new IllegalStateException("IDEMPOTENCY_RECEIPT_UNAVAILABLE");
            Receipt receipt = receipts.get(0);
            if (!command.action().equals(receipt.action()) || !Objects.equals(command.employeeId(), receipt.employeeId())
                    || !command.requestHash().equals(receipt.requestHash()))
                throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
            if (receipt.responseJson() != null) {
                try { return json.readValue(receipt.responseJson(), responseType); }
                catch (Exception e) { throw new IllegalStateException("Stored idempotency response is invalid", e); }
            }
            T result = action.get();
            try {
                jdbc.update("UPDATE employee_admin_command_receipt SET response_json=? WHERE actor_user_id=? AND request_id=?",
                        json.writeValueAsString(result), command.actorId(), command.requestId());
            } catch (Exception e) {
                throw new IllegalStateException("Could not persist idempotency response", e);
            }
            return result;
        }), "Employee command transaction did not return a result");
    }

    private record Receipt(String action, Long employeeId, String requestHash, String responseJson) {}
}
