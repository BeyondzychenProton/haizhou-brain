package com.haizhuo.brain.infrastructure.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.run.HarnessRunSpec;
import com.haizhuo.brain.platform.run.HarnessRunSpecRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 冻结 RunSpec 的 JDBC 读取（规格 §52.2）。写入发生在 createRun 内（事务 §10.2）。 */
@Repository
public class JdbcHarnessRunSpecRepository implements HarnessRunSpecRepository {
    private static final String COLUMNS = "run_id,definition_version_id,definition_bundle_id,definition_bundle_hash,"
            + "model_visible_tool_names_json,tool_view_hash,effective_capability_hash,channel_type,created_at";

    private final JdbcTemplate jdbc;

    public JdbcHarnessRunSpecRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public HarnessRunSpec save(HarnessRunSpec spec) {
        jdbc.update("INSERT INTO platform_agent_run_spec(run_id,definition_version_id,definition_bundle_id,"
                        + "definition_bundle_hash,model_visible_tool_names_json,tool_view_hash,effective_capability_hash,"
                        + "channel_type,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                spec.runId().value(), spec.definitionVersionId(), spec.definitionBundleId(), spec.definitionBundleHash(),
                spec.modelVisibleToolNamesJson(), spec.toolViewHash(), spec.effectiveCapabilityHash(),
                spec.channelType(), Timestamp.from(spec.createdAt()));
        return spec;
    }

    @Override
    public Optional<HarnessRunSpec> findByRunId(RunId runId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_agent_run_spec WHERE run_id=?",
                (rs, n) -> map(rs), runId.value()).stream().findFirst();
    }

    private HarnessRunSpec map(ResultSet rs) throws SQLException {
        return new HarnessRunSpec(new RunId(rs.getString("run_id")), rs.getLong("definition_version_id"),
                rs.getLong("definition_bundle_id"), rs.getString("definition_bundle_hash"),
                rs.getString("model_visible_tool_names_json"), rs.getString("tool_view_hash"),
                rs.getString("effective_capability_hash"), rs.getString("channel_type"),
                rs.getTimestamp("created_at").toInstant());
    }
}
