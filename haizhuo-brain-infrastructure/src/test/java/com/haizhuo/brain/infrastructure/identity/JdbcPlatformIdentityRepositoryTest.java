package com.haizhuo.brain.infrastructure.identity;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.security.identity.PlatformRole;
import com.haizhuo.brain.security.identity.PlatformUser;
import com.haizhuo.brain.security.identity.PlatformUserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JdbcPlatformIdentityRepository 的名单检索：关键词模糊匹配、状态过滤、分页与总数一致、
 * 角色批量读取（避免列表页 N+1），以及空結果集与空入参的边界。
 */
class JdbcPlatformIdentityRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcPlatformIdentityRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("identity");
        createIdentityTables(jdbc);
        repository = new JdbcPlatformIdentityRepository(jdbc, new NamedParameterJdbcTemplate(jdbc));
    }

    /** V4 中 repository 实际访问的最小列集合。 */
    private static void createIdentityTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE platform_user(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "mobile_normalized VARCHAR(32) NOT NULL,password_hash VARCHAR(255) NULL,"
                + "status VARCHAR(32) NOT NULL,auth_version BIGINT NOT NULL DEFAULT 1,"
                + "must_change_password BOOLEAN NOT NULL DEFAULT FALSE,created_by BIGINT NULL,"
                + "created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
                + "row_version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE platform_user_role(user_id BIGINT NOT NULL,role_code VARCHAR(32) NOT NULL,"
                + "granted_by BIGINT NULL,granted_at DATETIME(3) NOT NULL,PRIMARY KEY(user_id,role_code))");
    }

    private PlatformUser createUser(String mobile, PlatformUserStatus status) {
        return repository.createUser(mobile, null, status, false, new UserId(1), NOW);
    }

    @Test
    void searchReturnsEveryUserWhenNoFilterIsGiven() {
        createUser("+8613800000001", PlatformUserStatus.ACTIVE);
        createUser("+8613800000002", PlatformUserStatus.PENDING_ACTIVATION);
        createUser("+8613900000003", PlatformUserStatus.DISABLED);

        List<PlatformUser> users = repository.searchUsers(null, null, 10, 0);

        assertEquals(3, users.size());
        assertEquals(3, repository.countUsers(null, null));
        // 稳定的倒序，保证翻页不会出现重复或漏行。
        assertEquals("+8613900000003", users.get(0).mobileNormalized());
        assertEquals("+8613800000002", users.get(1).mobileNormalized());
        assertEquals("+8613800000001", users.get(2).mobileNormalized());
    }

    @Test
    void keywordMatchesMobileAsSubstring() {
        createUser("+8613800000001", PlatformUserStatus.ACTIVE);
        createUser("+8613900000002", PlatformUserStatus.ACTIVE);

        List<PlatformUser> users = repository.searchUsers("1380", null, 10, 0);

        assertEquals(1, users.size());
        assertEquals("+8613800000001", users.get(0).mobileNormalized());
        assertEquals(1, repository.countUsers("1380", null));
    }

    @Test
    void blankKeywordIsTreatedAsNoFilter() {
        createUser("+8613800000001", PlatformUserStatus.ACTIVE);

        assertEquals(1, repository.searchUsers("   ", null, 10, 0).size());
        assertEquals(1, repository.countUsers("   ", null));
    }

    @Test
    void statusFilterSelectsOnlyMatchingAccounts() {
        PlatformUser active = createUser("+8613800000001", PlatformUserStatus.ACTIVE);
        createUser("+8613800000002", PlatformUserStatus.PENDING_ACTIVATION);

        List<PlatformUser> users = repository.searchUsers(null, PlatformUserStatus.ACTIVE, 10, 0);

        assertEquals(List.of(active.id()), users.stream().map(PlatformUser::id).toList());
        assertEquals(1, repository.countUsers(null, PlatformUserStatus.ACTIVE));
    }

    @Test
    void keywordAndStatusCombineAndPaginationKeepsTotalStable() {
        createUser("+8613800000001", PlatformUserStatus.ACTIVE);
        createUser("+8613800000002", PlatformUserStatus.ACTIVE);
        createUser("+8613800000003", PlatformUserStatus.ACTIVE);
        createUser("+8613800000004", PlatformUserStatus.DISABLED);

        long total = repository.countUsers("1380", PlatformUserStatus.ACTIVE);
        assertEquals(3, total);

        List<String> firstPage = mobileOf(repository.searchUsers("1380", PlatformUserStatus.ACTIVE, 2, 0));
        List<String> secondPage = mobileOf(repository.searchUsers("1380", PlatformUserStatus.ACTIVE, 2, 2));

        assertEquals(List.of("+8613800000003", "+8613800000002"), firstPage);
        assertEquals(List.of("+8613800000001"), secondPage);
        assertEquals(total, firstPage.size() + secondPage.size());
    }

    @Test
    void rolesAreLoadedInOneBatchQuery() {
        PlatformUser admin = createUser("+8613800000001", PlatformUserStatus.ACTIVE);
        PlatformUser member = createUser("+8613800000002", PlatformUserStatus.ACTIVE);
        repository.grantRole(admin.id(), PlatformRole.PLATFORM_ADMIN, new UserId(1), NOW);
        repository.grantRole(admin.id(), PlatformRole.USER, new UserId(1), NOW);
        repository.grantRole(member.id(), PlatformRole.USER, new UserId(1), NOW);

        Map<UserId, Set<PlatformRole>> roles = repository.findRoles(List.of(admin.id(), member.id()));

        assertEquals(Set.of(PlatformRole.PLATFORM_ADMIN, PlatformRole.USER), roles.get(admin.id()));
        assertEquals(Set.of(PlatformRole.USER), roles.get(member.id()));
        assertEquals(Set.of(PlatformRole.PLATFORM_ADMIN, PlatformRole.USER), repository.findRoles(admin.id()));
    }

    @Test
    void userWithoutAnyRoleStillAppearsInBatchResult() {
        PlatformUser user = createUser("+8613800000001", PlatformUserStatus.ACTIVE);

        Map<UserId, Set<PlatformRole>> roles = repository.findRoles(List.of(user.id()));

        assertTrue(roles.get(user.id()).isEmpty());
    }

    @Test
    void emptyInputsReturnEmptyResultsInsteadOfFailing() {
        assertTrue(repository.findRoles(List.of()).isEmpty());
        assertTrue(repository.searchUsers(null, null, 10, 0).isEmpty());
        assertEquals(0, repository.countUsers(null, null));
    }

    private static List<String> mobileOf(List<PlatformUser> users) {
        return users.stream().map(PlatformUser::mobileNormalized).toList();
    }
}
