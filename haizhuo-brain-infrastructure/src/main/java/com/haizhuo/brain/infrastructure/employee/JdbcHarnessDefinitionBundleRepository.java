package com.haizhuo.brain.infrastructure.employee;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeConfiguration;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundle;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleRepository;
import com.haizhuo.brain.platform.employee.runtime.PublishedToolSchema;
import com.haizhuo.brain.runtime.api.model.RuntimeWorkspaceFile;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 不可变、内容寻址的运行时能力包的 JDBC 适配器（规格 §52.1）。只允许插入。 */
@Repository
public class JdbcHarnessDefinitionBundleRepository implements HarnessDefinitionBundleRepository {
    private static final TypeReference<List<PublishedToolSchema>> TOOL_CATALOG = new TypeReference<>() { };
    private static final TypeReference<List<RuntimeWorkspaceFile>> WORKSPACE_FILES = new TypeReference<>() { };
    private static final String COLUMNS = "id,definition_version_id,employee_name,model_provider,model_name,max_iterations,"
            + "instructions,workspace_manifest_json,workspace_content_hash,tool_catalog_json,tool_catalog_hash,"
            + "subagent_manifest_json,policy_json,configuration_json,workspace_files_json,bundle_hash,created_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcHarnessDefinitionBundleRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public HarnessDefinitionBundle save(HarnessDefinitionBundle bundle) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO agent_definition_runtime_bundle(definition_version_id,employee_name,model_provider,model_name,"
                            + "max_iterations,instructions,workspace_manifest_json,workspace_content_hash,tool_catalog_json,"
                            + "tool_catalog_hash,subagent_manifest_json,policy_json,configuration_json,workspace_files_json,bundle_hash,created_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", new String[]{"id"});
            statement.setLong(1, bundle.definitionVersionId());
            statement.setString(2, bundle.employeeName());
            statement.setString(3, bundle.modelProvider());
            statement.setString(4, bundle.modelName());
            statement.setInt(5, bundle.maxIterations());
            statement.setString(6, bundle.instructions());
            statement.setString(7, bundle.workspaceManifestJson());
            statement.setString(8, bundle.workspaceContentHash());
            statement.setString(9, writeCatalog(bundle.toolCatalog()));
            statement.setString(10, bundle.toolCatalogHash());
            statement.setString(11, bundle.subAgentManifestJson());
            statement.setString(12, bundle.policyJson());
            statement.setString(13, writeJson(bundle.configuration()));
            statement.setString(14, writeFiles(bundle.workspaceFiles()));
            statement.setString(15, bundle.bundleHash());
            statement.setTimestamp(16, Timestamp.from(bundle.createdAt()));
            return statement;
        }, key);
        Number generated = key.getKey();
        if (generated == null) throw new IllegalStateException("Database did not return a runtime bundle id");
        return new HarnessDefinitionBundle(generated.longValue(), bundle.definitionVersionId(), bundle.employeeName(),
                bundle.instructions(), bundle.modelProvider(), bundle.modelName(), bundle.maxIterations(),
                bundle.workspaceManifestJson(), bundle.workspaceContentHash(), bundle.toolCatalog(),
                bundle.toolCatalogHash(), bundle.subAgentManifestJson(), bundle.policyJson(), bundle.bundleHash(),
                bundle.configuration(), bundle.workspaceFiles(), bundle.createdAt());
    }

    @Override
    public Optional<HarnessDefinitionBundle> findByDefinitionVersionId(long definitionVersionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agent_definition_runtime_bundle WHERE definition_version_id=?",
                (rs, n) -> map(rs), definitionVersionId).stream().findFirst();
    }

    @Override
    public Optional<HarnessDefinitionBundle> findByBundleHash(String bundleHash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agent_definition_runtime_bundle WHERE bundle_hash=?",
                (rs, n) -> map(rs), bundleHash).stream().findFirst();
    }

    private HarnessDefinitionBundle map(ResultSet rs) throws SQLException {
        return new HarnessDefinitionBundle(rs.getLong("id"), rs.getLong("definition_version_id"),
                rs.getString("employee_name"), rs.getString("instructions"), rs.getString("model_provider"),
                rs.getString("model_name"), rs.getInt("max_iterations"), rs.getString("workspace_manifest_json"),
                rs.getString("workspace_content_hash"), readCatalog(rs.getString("tool_catalog_json")),
                rs.getString("tool_catalog_hash"), rs.getString("subagent_manifest_json"), rs.getString("policy_json"),
                rs.getString("bundle_hash"), readConfiguration(rs.getString("configuration_json")),
                readFiles(rs.getString("workspace_files_json")), rs.getTimestamp("created_at").toInstant());
    }

    private String writeCatalog(List<PublishedToolSchema> catalog) {
        try {
            return json.writeValueAsString(catalog);
        } catch (Exception error) {
            throw new IllegalStateException("Could not serialize the tool catalog", error);
        }
    }

    private List<PublishedToolSchema> readCatalog(String value) {
        try {
            return json.readValue(value, TOOL_CATALOG);
        } catch (Exception error) {
            throw new IllegalStateException("Could not read the tool catalog", error);
        }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Could not serialize runtime bundle metadata", error); }
    }

    private EmployeeRuntimeConfiguration readConfiguration(String value) {
        if (value == null || value.isBlank()) return EmployeeRuntimeConfiguration.legacyStable();
        try { return json.readValue(value, EmployeeRuntimeConfiguration.class); }
        catch (Exception error) { throw new IllegalStateException("Could not read the employee runtime configuration", error); }
    }

    private String writeFiles(List<RuntimeWorkspaceFile> files) {
        return writeJson(files);
    }

    private List<RuntimeWorkspaceFile> readFiles(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, WORKSPACE_FILES); }
        catch (Exception error) { throw new IllegalStateException("Could not read frozen workspace files", error); }
    }
}
