package com.haizhuo.brain.infrastructure.employee;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.employee.ModelConnectionBindingStore;
import com.haizhuo.brain.platform.run.ModelConnectionBindingUnavailableException;
import com.haizhuo.brain.runtime.api.model.RuntimeModelConnectionRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 使用 JDBC 持久化由定义和 Run 冻结的精确模型连接修订。 */
@Repository
public class JdbcModelConnectionBindingStore implements ModelConnectionBindingStore {
    private final JdbcTemplate jdbc;

    public JdbcModelConnectionBindingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void freezeDefinitionVersionBinding(long employeeId, int draftRevision, long definitionVersionId) {
        Optional<RuntimeModelConnectionRef> existing = findRawVersionBinding(definitionVersionId);
        List<DraftBinding> draftBindings = jdbc.query("""
                SELECT draft_revision, connection_id, connection_revision, connection_content_hash, reference_kind
                FROM agent_definition_draft_model_connection
                WHERE employee_id=? FOR UPDATE
                """, (rs, row) -> new DraftBinding(rs.getInt("draft_revision"), reference(rs)), employeeId);
        if (draftBindings.isEmpty()) return;
        DraftBinding draft = draftBindings.get(0);
        if (draft.draftRevision() != draftRevision)
            throw new IllegalStateException("Draft model connection changed during publication");
        if (existing.isPresent()) {
            if (!existing.get().equals(draft.reference()))
                throw new IllegalStateException("Published model connection binding is immutable");
            if (!isValidRevision(draft.reference(), false))
                throw new IllegalStateException("Published model connection revision is unavailable");
            return;
        }
        if (!isValidRevision(draft.reference(), true))
            throw new IllegalStateException("Draft model connection revision is unavailable");
        jdbc.update("""
                INSERT INTO agent_definition_version_model_connection
                    (definition_version_id, connection_id, connection_revision, connection_content_hash,
                     reference_kind, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                """, definitionVersionId, draft.reference().connectionId(), draft.reference().revision(),
                draft.reference().contentHash(), draft.reference().kind().name());
    }

    @Override
    public Optional<RuntimeModelConnectionRef> findDefinitionVersionBinding(long definitionVersionId,
                                                                             String expectedProvider) {
        List<RuntimeModelConnectionRef> found = jdbc.query("""
                SELECT v.connection_id, v.connection_revision, v.connection_content_hash, v.reference_kind
                FROM agent_definition_version_model_connection v
                JOIN platform_model_connection c ON c.connection_id=v.connection_id
                JOIN platform_model_connection_revision r
                  ON r.connection_id=v.connection_id AND r.revision=v.connection_revision
                WHERE v.definition_version_id=?
                  AND LOWER(c.provider)=LOWER(?)
                  AND c.connection_kind=v.reference_kind
                  AND r.content_hash=v.connection_content_hash
                """, (rs, row) -> reference(rs), definitionVersionId, expectedProvider);
        if (found.size() > 1) throw new IllegalStateException("Definition model connection binding is not unique");
        return found.stream().findFirst();
    }

    @Override
    @Transactional
    public RuntimeModelConnectionRef freezeRunBinding(RunId runId, String executorRoleId,
                                                       long executorDefinitionVersionId,
                                                       String expectedProvider,
                                                       RuntimeModelConnectionRef reference) {
        RuntimeModelConnectionRef versionReference = findDefinitionVersionBinding(
                        executorDefinitionVersionId, expectedProvider)
                .orElseThrow(ModelConnectionBindingUnavailableException::new);
        if (!versionReference.equals(reference)) throw new ModelConnectionBindingUnavailableException();

        try {
            jdbc.update("""
                    INSERT INTO platform_run_model_connection_binding
                        (run_id, executor_role_id, executor_definition_version_id, connection_id,
                         connection_revision, connection_content_hash, reference_kind, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                    """, runId.value(), executorRoleId, executorDefinitionVersionId,
                    reference.connectionId(), reference.revision(), reference.contentHash(), reference.kind().name());
        } catch (DuplicateKeyException duplicate) {
            // 重试只能复用字节内容完全相同的不可变执行者绑定。
        }
        List<RuntimeModelConnectionRef> persisted = jdbc.query("""
                SELECT connection_id, connection_revision, connection_content_hash, reference_kind
                FROM platform_run_model_connection_binding
                WHERE run_id=? AND executor_role_id=? AND executor_definition_version_id=?
                """, (rs, row) -> reference(rs), runId.value(), executorRoleId, executorDefinitionVersionId);
        if (persisted.size() != 1 || !reference.equals(persisted.get(0)))
            throw new ModelConnectionBindingUnavailableException();
        return persisted.get(0);
    }

    private Optional<RuntimeModelConnectionRef> findRawVersionBinding(long definitionVersionId) {
        List<RuntimeModelConnectionRef> found = jdbc.query("""
                SELECT connection_id, connection_revision, connection_content_hash, reference_kind
                FROM agent_definition_version_model_connection WHERE definition_version_id=?
                """, (rs, row) -> reference(rs), definitionVersionId);
        if (found.size() > 1) throw new IllegalStateException("Definition model connection binding is not unique");
        return found.stream().findFirst();
    }

    private boolean isValidRevision(RuntimeModelConnectionRef reference, boolean requireActive) {
        String statusClause = requireActive ? "AND c.status='ACTIVE'" : "";
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM platform_model_connection c
                JOIN platform_model_connection_revision r ON r.connection_id=c.connection_id
                WHERE c.connection_id=? AND r.revision=? AND r.content_hash=?
                  AND c.connection_kind=?
                """ + statusClause, Integer.class, reference.connectionId(), reference.revision(),
                reference.contentHash(), reference.kind().name());
        return count != null && count == 1;
    }

    private RuntimeModelConnectionRef reference(ResultSet rs) throws SQLException {
        RuntimeModelConnectionRef.ReferenceKind kind;
        try {
            kind = RuntimeModelConnectionRef.ReferenceKind.valueOf(rs.getString("reference_kind"));
        } catch (RuntimeException invalidKind) {
            throw new ModelConnectionBindingUnavailableException();
        }
        return new RuntimeModelConnectionRef(rs.getString("connection_id"),
                rs.getInt("connection_revision"), rs.getString("connection_content_hash"), kind);
    }

    private record DraftBinding(int draftRevision, RuntimeModelConnectionRef reference) {}
}
