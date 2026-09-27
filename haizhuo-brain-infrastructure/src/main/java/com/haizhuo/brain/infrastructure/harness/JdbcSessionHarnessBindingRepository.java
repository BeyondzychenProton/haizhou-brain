package com.haizhuo.brain.infrastructure.harness;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.harness.SessionHarnessBinding;
import com.haizhuo.brain.platform.harness.SessionHarnessBindingRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 会话 → Harness 绑定的 JDBC 适配器（规格 §53.2）。scope 唯一；键唯一。 */
@Repository
public class JdbcSessionHarnessBindingRepository implements SessionHarnessBindingRepository {
    private final JdbcTemplate jdbc;

    public JdbcSessionHarnessBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SessionHarnessBinding> findByScope(UserId userId, SessionId platformSessionId,
                                                       long definitionVersionId, int generation) {
        return jdbc.query("SELECT id,user_id,session_id,employee_id,definition_version_id,generation,"
                        + "harness_session_key,workspace_runtime_key,bridge_snapshot_id,created_at "
                        + "FROM platform_session_harness_binding "
                        + "WHERE user_id=? AND session_id=? AND definition_version_id=? AND generation=?",
                (rs, n) -> map(rs), userId.value(), platformSessionId.value(), definitionVersionId, generation)
                .stream().findFirst();
    }

    @Override
    public SessionHarnessBinding save(SessionHarnessBinding binding) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO platform_session_harness_binding(user_id,session_id,employee_id,definition_version_id,"
                            + "generation,harness_session_key,workspace_runtime_key,bridge_snapshot_id,created_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)", new String[]{"id"});
            statement.setLong(1, binding.userId().value());
            statement.setString(2, binding.platformSessionId().value());
            statement.setLong(3, binding.employeeId());
            statement.setLong(4, binding.definitionVersionId());
            statement.setInt(5, binding.generation());
            statement.setString(6, binding.harnessSessionKey());
            statement.setString(7, binding.workspaceRuntimeKey());
            statement.setLong(8, binding.bridgeSnapshotId());
            statement.setTimestamp(9, Timestamp.from(binding.createdAt()));
            return statement;
        }, key);
        Number generated = key.getKey();
        if (generated == null) throw new IllegalStateException("Database did not return a harness binding id");
        return new SessionHarnessBinding(generated.longValue(), binding.userId(), binding.platformSessionId(),
                binding.employeeId(), binding.definitionVersionId(), binding.generation(), binding.harnessSessionKey(),
                binding.workspaceRuntimeKey(), binding.bridgeSnapshotId(), binding.createdAt());
    }

    private SessionHarnessBinding map(ResultSet rs) throws SQLException {
        return new SessionHarnessBinding(rs.getLong("id"), new UserId(rs.getLong("user_id")),
                new SessionId(rs.getString("session_id")), rs.getLong("employee_id"),
                rs.getLong("definition_version_id"), rs.getInt("generation"), rs.getString("harness_session_key"),
                rs.getString("workspace_runtime_key"), rs.getLong("bridge_snapshot_id"),
                rs.getTimestamp("created_at").toInstant());
    }
}
