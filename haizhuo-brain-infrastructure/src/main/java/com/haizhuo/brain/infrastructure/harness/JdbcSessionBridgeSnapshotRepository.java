package com.haizhuo.brain.infrastructure.harness;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.harness.SessionBridgeSnapshot;
import com.haizhuo.brain.platform.harness.SessionBridgeSnapshotRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 一次性桥接快照的 JDBC 适配器（规格 §53.1）。只允许插入。 */
@Repository
public class JdbcSessionBridgeSnapshotRepository implements SessionBridgeSnapshotRepository {
    private final JdbcTemplate jdbc;

    public JdbcSessionBridgeSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SessionBridgeSnapshot> findById(long id) {
        return jdbc.query("SELECT id,user_id,session_id,definition_version_id,generation,source_event_sequence,"
                        + "conversation_summary,stable_business_facts_json,schema_version,content_hash,created_at "
                        + "FROM platform_session_bridge_snapshot WHERE id=?",
                (rs, n) -> map(rs), id).stream().findFirst();
    }

    @Override
    public SessionBridgeSnapshot save(SessionBridgeSnapshot snapshot) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO platform_session_bridge_snapshot(user_id,session_id,definition_version_id,generation,"
                            + "source_event_sequence,conversation_summary,stable_business_facts_json,schema_version,"
                            + "content_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)", new String[]{"id"});
            statement.setLong(1, snapshot.userId().value());
            statement.setString(2, snapshot.platformSessionId().value());
            statement.setLong(3, snapshot.definitionVersionId());
            statement.setInt(4, snapshot.generation());
            statement.setInt(5, snapshot.sourceEventSequence());
            statement.setString(6, snapshot.conversationSummary());
            statement.setString(7, snapshot.stableBusinessFactsJson());
            statement.setInt(8, snapshot.schemaVersion());
            statement.setString(9, snapshot.contentHash());
            statement.setTimestamp(10, Timestamp.from(snapshot.createdAt()));
            return statement;
        }, key);
        Number generated = key.getKey();
        if (generated == null) throw new IllegalStateException("Database did not return a bridge snapshot id");
        return new SessionBridgeSnapshot(generated.longValue(), snapshot.userId(), snapshot.platformSessionId(),
                snapshot.definitionVersionId(), snapshot.generation(), snapshot.sourceEventSequence(),
                snapshot.conversationSummary(), snapshot.stableBusinessFactsJson(), snapshot.schemaVersion(),
                snapshot.contentHash(), snapshot.createdAt());
    }

    private SessionBridgeSnapshot map(ResultSet rs) throws SQLException {
        return new SessionBridgeSnapshot(rs.getLong("id"), new UserId(rs.getLong("user_id")),
                new SessionId(rs.getString("session_id")), rs.getLong("definition_version_id"), rs.getInt("generation"),
                rs.getInt("source_event_sequence"), rs.getString("conversation_summary"),
                rs.getString("stable_business_facts_json"), rs.getInt("schema_version"), rs.getString("content_hash"),
                rs.getTimestamp("created_at").toInstant());
    }
}
