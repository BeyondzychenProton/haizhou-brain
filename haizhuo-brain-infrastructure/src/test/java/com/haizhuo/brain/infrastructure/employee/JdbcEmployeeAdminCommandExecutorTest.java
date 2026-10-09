package com.haizhuo.brain.infrastructure.employee;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.EmployeeAdminCommandExecutor;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JdbcEmployeeAdminCommandExecutorTest {
    private JdbcTemplate jdbc;
    private JdbcEmployeeAdminCommandExecutor commands;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:employee_commands_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE employee_admin_command_receipt(actor_user_id BIGINT NOT NULL,request_id VARCHAR(128) NOT NULL,"
                + "action_code VARCHAR(64) NOT NULL,employee_id BIGINT NULL,request_hash CHAR(64) NOT NULL,response_json CLOB NULL,"
                + "created_at TIMESTAMP NOT NULL,PRIMARY KEY(actor_user_id,request_id))");
        jdbc.execute("CREATE TABLE command_effect(id BIGINT PRIMARY KEY,effect_value VARCHAR(40) NOT NULL)");
        commands = new JdbcEmployeeAdminCommandExecutor(jdbc, new DataSourceTransactionManager(dataSource),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void sameRequestReplaysResponseAndDifferentPayloadConflicts() {
        var command = new EmployeeAdminCommandExecutor.Command(42, "SET_STATUS", 9L, "request-1", "a".repeat(64));
        AtomicInteger executions = new AtomicInteger();

        CommandReply original = commands.execute(command, CommandReply.class, () -> {
            int invocation = executions.incrementAndGet();
            jdbc.update("INSERT INTO command_effect(id,effect_value) VALUES(?,?)", invocation, "applied");
            return new CommandReply("employee-9", invocation);
        });
        CommandReply replay = commands.execute(command, CommandReply.class,
                () -> new CommandReply("should-not-run", executions.incrementAndGet()));

        assertEquals(original, replay);
        assertEquals(1, executions.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM command_effect", Integer.class));
        assertThrows(IllegalStateException.class, () -> commands.execute(
                new EmployeeAdminCommandExecutor.Command(42, "SET_STATUS", 9L, "request-1", "b".repeat(64)),
                CommandReply.class, () -> new CommandReply("wrong", 0)), "相同 requestId 不接受不同请求内容");
    }

    @Test
    void failedMutationRollsBackEffectAndReceipt() {
        var command = new EmployeeAdminCommandExecutor.Command(42, "CREATE", null, "request-rollback", "c".repeat(64));

        assertThrows(IllegalStateException.class, () -> commands.execute(command, CommandReply.class, () -> {
            jdbc.update("INSERT INTO command_effect(id,effect_value) VALUES(1,'partial')");
            throw new IllegalStateException("simulated failure");
        }));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_effect", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM employee_admin_command_receipt", Integer.class));
    }

    private record CommandReply(String employee, int revision) {}
}
