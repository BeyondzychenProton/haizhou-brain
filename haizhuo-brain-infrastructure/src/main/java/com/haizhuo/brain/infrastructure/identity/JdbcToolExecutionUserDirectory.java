package com.haizhuo.brain.infrastructure.identity;

import com.haizhuo.brain.platform.tool.ToolExecutionUserDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 为网关执行期第 4 步实时查询用户状态（规格 §37）。 */
@Component
public class JdbcToolExecutionUserDirectory implements ToolExecutionUserDirectory {

    private final JdbcTemplate jdbc;

    public JdbcToolExecutionUserDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isUserActive(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_user WHERE id=? AND status='ACTIVE'", Integer.class, userId);
        return count != null && count > 0;
    }
}
