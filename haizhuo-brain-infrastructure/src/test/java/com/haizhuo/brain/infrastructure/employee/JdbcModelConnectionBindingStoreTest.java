package com.haizhuo.brain.infrastructure.employee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.run.ModelConnectionBindingUnavailableException;
import com.haizhuo.brain.runtime.api.model.RuntimeModelConnectionRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcModelConnectionBindingStoreTest {
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:model_connection_binding;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    private final JdbcModelConnectionBindingStore store = new JdbcModelConnectionBindingStore(jdbc);
    private final RuntimeModelConnectionRef reference = new RuntimeModelConnectionRef(
            "conn-1", 2, "d".repeat(64));

    @BeforeEach
    void createSchema() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE platform_model_connection (
                    connection_id VARCHAR(64) PRIMARY KEY,
                    provider VARCHAR(32) NOT NULL,
                    connection_kind VARCHAR(32) NOT NULL,
                    status VARCHAR(24) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE platform_model_connection_revision (
                    connection_id VARCHAR(64) NOT NULL,
                    revision INT NOT NULL,
                    content_hash CHAR(64) NOT NULL,
                    PRIMARY KEY (connection_id, revision)
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_definition_draft_model_connection (
                    employee_id BIGINT PRIMARY KEY,
                    draft_revision INT NOT NULL,
                    connection_id VARCHAR(64) NOT NULL,
                    connection_revision INT NOT NULL,
                    connection_content_hash CHAR(64) NOT NULL,
                    reference_kind VARCHAR(32) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_definition_version_model_connection (
                    definition_version_id BIGINT PRIMARY KEY,
                    connection_id VARCHAR(64) NOT NULL,
                    connection_revision INT NOT NULL,
                    connection_content_hash CHAR(64) NOT NULL,
                    reference_kind VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE platform_run_model_connection_binding (
                    run_id VARCHAR(36) NOT NULL,
                    executor_role_id VARCHAR(64) NOT NULL,
                    executor_definition_version_id BIGINT NOT NULL,
                    connection_id VARCHAR(64) NOT NULL,
                    connection_revision INT NOT NULL,
                    connection_content_hash CHAR(64) NOT NULL,
                    reference_kind VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (run_id, executor_role_id, executor_definition_version_id)
                )
                """);
    }

    @Test
    void publisherCopiesOnlyTheExactActiveDraftAndRunBindingIsImmutableAndIdempotent() {
        insertConnection(reference, "openai", "MANAGED_REVISION", "ACTIVE");
        jdbc.update("""
                INSERT INTO agent_definition_draft_model_connection
                    (employee_id, draft_revision, connection_id, connection_revision,
                     connection_content_hash, reference_kind)
                VALUES (?,?,?,?,?,?)
                """, 71L, 6, reference.connectionId(), reference.revision(), reference.contentHash(),
                reference.kind().name());

        store.freezeDefinitionVersionBinding(71L, 6, 909L);

        assertEquals(reference, store.findDefinitionVersionBinding(909L, "openai").orElseThrow());
        jdbc.update("UPDATE platform_model_connection SET status='DISABLED' WHERE connection_id=?",
                reference.connectionId());
        assertEquals(reference, store.findDefinitionVersionBinding(909L, "openai").orElseThrow());
        assertEquals(reference, store.freezeRunBinding(new RunId("run-909"), "coordinator", 909L,
                "openai", reference));
        assertEquals(reference, store.freezeRunBinding(new RunId("run-909"), "coordinator", 909L,
                "openai", reference));
    }

    @Test
    void draftRevisionOrActiveStatusMismatchCannotPublishBinding() {
        insertConnection(reference, "openai", "MANAGED_REVISION", "DISABLED");
        jdbc.update("""
                INSERT INTO agent_definition_draft_model_connection
                    (employee_id, draft_revision, connection_id, connection_revision,
                     connection_content_hash, reference_kind)
                VALUES (?,?,?,?,?,?)
                """, 72L, 7, reference.connectionId(), reference.revision(), reference.contentHash(),
                reference.kind().name());

        assertThrows(IllegalStateException.class,
                () -> store.freezeDefinitionVersionBinding(72L, 7, 910L));
        assertFalse(store.findDefinitionVersionBinding(910L, "openai").isPresent());
        assertThrows(IllegalStateException.class,
                () -> store.freezeDefinitionVersionBinding(72L, 6, 911L));
    }

    @Test
    void providerHashAndPriorRunBindingMustMatchExactly() {
        insertConnection(reference, "openai", "MANAGED_REVISION", "ACTIVE");
        jdbc.update("""
                INSERT INTO agent_definition_version_model_connection
                    (definition_version_id, connection_id, connection_revision, connection_content_hash,
                     reference_kind, created_at)
                VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)
                """, 912L, reference.connectionId(), reference.revision(), reference.contentHash(),
                reference.kind().name());

        assertFalse(store.findDefinitionVersionBinding(912L, "dashscope").isPresent());
        RuntimeModelConnectionRef wrongHash = new RuntimeModelConnectionRef(
                "conn-1", 2, "e".repeat(64));
        assertThrows(ModelConnectionBindingUnavailableException.class,
                () -> store.freezeRunBinding(new RunId("run-912"), "coordinator", 912L,
                        "openai", wrongHash));
        assertEquals(reference, store.freezeRunBinding(new RunId("run-912"), "coordinator", 912L,
                "openai", reference));
        assertThrows(ModelConnectionBindingUnavailableException.class,
                () -> store.freezeRunBinding(new RunId("run-912"), "coordinator", 912L,
                        "openai", wrongHash));
    }

    @Test
    void definitionBindingWithStaleRevisionHashIsNotResolved() {
        insertConnection(reference, "openai", "MANAGED_REVISION", "ACTIVE");
        jdbc.update("""
                INSERT INTO agent_definition_version_model_connection
                    (definition_version_id, connection_id, connection_revision, connection_content_hash,
                     reference_kind, created_at)
                VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)
                """, 913L, reference.connectionId(), reference.revision(), "e".repeat(64),
                reference.kind().name());

        assertFalse(store.findDefinitionVersionBinding(913L, "openai").isPresent());
        assertThrows(ModelConnectionBindingUnavailableException.class,
                () -> store.freezeRunBinding(new RunId("run-913"), "coordinator", 913L,
                        "openai", reference));
    }

    private void insertConnection(RuntimeModelConnectionRef ref, String provider, String kind, String status) {
        jdbc.update("INSERT INTO platform_model_connection VALUES (?,?,?,?)", ref.connectionId(), provider, kind,
                status);
        jdbc.update("INSERT INTO platform_model_connection_revision VALUES (?,?,?)", ref.connectionId(),
                ref.revision(), ref.contentHash());
    }
}
