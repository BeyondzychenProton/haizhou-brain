package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.platform.run.RunArtifactBlobReferenceQuery;

import java.sql.PreparedStatement;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/**
 * 精确查询所有状态的引用；写入进行期间，STAGED 行会保护对应 Blob。
 */
@Repository
public class JdbcRunArtifactBlobReferenceQuery implements RunArtifactBlobReferenceQuery {
    private static final String EXISTS_SQL =
            "SELECT 1 FROM platform_run_artifact WHERE blob_ref=? LIMIT 1";
    private final JdbcTemplate jdbc;

    public JdbcRunArtifactBlobReferenceQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isBlobRefReferenced(String blobRef) {
        Objects.requireNonNull(blobRef, "blobRef");
        ResultSetExtractor<Boolean> exists = resultSet -> resultSet.next();
        Boolean referenced = jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(EXISTS_SQL);
            statement.setQueryTimeout(1);
            statement.setString(1, blobRef);
            return statement;
        }, exists);
        return Boolean.TRUE.equals(referenced);
    }
}
