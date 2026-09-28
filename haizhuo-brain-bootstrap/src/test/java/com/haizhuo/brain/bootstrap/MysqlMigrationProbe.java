package com.haizhuo.brain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;

/**
 * 真机 MySQL 验证（手动运行，不在常规 {@code mvn test} 里跑——类名不匹配 surefire 默认 include）。
 * 把 AgentScope 会话表的验证从 H2 方言升级到真实 MySQL + {@code MysqlDialect}：
 *
 * <pre>
 * mvn -o -pl haizhuo-brain-bootstrap test -Dtest=MysqlMigrationProbe -Dmysql.probe.password=&lt;密码&gt;
 *   [-Dmysql.probe.user=root] [-Dmysql.probe.host=127.0.0.1:3306]
 * </pre>
 *
 * 生命周期：建一次性 scratch 库 {@code haizhuo_brain_v13probe} → 跑全量 Flyway 迁移 → 建库/验证
 * → {@code @AfterEach} 删库。未给密码则整组跳过，不会碰任何库。
 *
 * <p>覆盖两条：①迁移齐全 + 严格 2 参构造可用 + 状态读写往返（真机启动崩的就是这一步）；
 * ②P0 准入条件——Harness 挂起后彻底重建（新 Store 实例 + 新 Harness），从 MySQL 状态恢复
 * ToolResult 并给出最终回答。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MysqlMigrationProbe {

    private static final String DB = "haizhuo_brain_v13probe";

    private String user;
    private String password;
    private String host;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        password = System.getProperty("mysql.probe.password");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "需要 -Dmysql.probe.password=<密码> 才运行真机验证（默认跳过）");
        user = System.getProperty("mysql.probe.user", "root");
        host = System.getProperty("mysql.probe.host", "127.0.0.1:3306");

        try (Connection c = rootConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT VERSION()")) {
                rs.next();
                System.out.println("[probe] MySQL VERSION() = " + rs.getString(1));
            }
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB);
        }

        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(url(host, DB));
        ds.setUser(user);
        ds.setPassword(password);
        dataSource = ds;

        var result = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        System.out.println("[probe] flyway executed=" + result.migrationsExecuted + ", success=" + result.success
                + ", target=" + result.targetSchemaVersion);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (password == null || password.isBlank()) {
            return;
        }
        try (Connection c = rootConnection(); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            System.out.println("[probe] scratch database dropped");
        }
    }

    @Test
    void migrationsProduceASchemaTheStrictStoreAccepts() throws Exception {
        JdbcAgentStateStore store = new JdbcAgentStateStore(dataSource, new MysqlDialect());
        System.out.println("[probe] strict 2-arg JdbcAgentStateStore constructed OK");

        AgentState state = AgentState.builder().sessionId("hs-mysql").userId("user-mysql")
                .summary("real mysql roundtrip").build();
        store.save("user-mysql", "hs-mysql", "agent_state", state);
        assertTrue(store.exists("user-mysql", "hs-mysql"));
        AgentState reloaded = store.get("user-mysql", "hs-mysql", "agent_state", AgentState.class).orElseThrow();
        assertEquals("hs-mysql", reloaded.getSessionId());
        assertEquals("real mysql roundtrip", reloaded.getSummary());
        assertTrue(store.listSessionIds("user-mysql").contains("hs-mysql"));
        store.delete("user-mysql", "hs-mysql");
        assertFalse(store.exists("user-mysql", "hs-mysql"));
        System.out.println("[probe] store roundtrip on real MySQL OK");

        try (Connection c = ((MysqlDataSource) dataSource).getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW CREATE TABLE agentscope_sessions")) {
            rs.next();
            System.out.println("[probe] SHOW CREATE TABLE:\n" + rs.getString(2));
        }
    }

    /** P0 准入条件：挂起 → 关闭 → 全新 Store + 全新 Harness → ToolResult 恢复。 */
    @Test
    void harnessResumesExternalToolAfterFullRestart() throws Exception {
        ToolCallingModel model = new ToolCallingModel();
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(ToolSchema.builder().name("meeting.reserve").description("P0 schema")
                .parameters(Map.of("type", "object", "properties", Map.of())).build());
        RuntimeContext context = RuntimeContext.builder().userId("user-a").sessionId("hs-restart").build();

        HarnessAgent first = harness(model, toolkit);
        try {
            List<AgentEvent> events = first.streamEvents("reserve room", context).collectList().block();
            assertTrue(events.stream().anyMatch(RequireExternalExecutionEvent.class::isInstance),
                    "首轮必须因外部工具挂起");
        } finally {
            first.close();
        }

        JdbcAgentStateStore restartedStore = new JdbcAgentStateStore(dataSource, new MysqlDialect());
        assertTrue(restartedStore.exists("user-a", "hs-restart"), "挂起状态必须已落 MySQL");
        assertTrue(restartedStore.listSessionIds("user-a").contains("hs-restart"));

        HarnessAgent restarted = harness(model, toolkit);
        try {
            ToolResultBlock result = ToolResultBlock.builder().id("tool-use-1").name("meeting.reserve")
                    .output(TextBlock.builder().text("room available").build()).build();
            Msg answer = restarted.call(new ToolResultMessage(result), context).block();
            assertEquals("reservation complete", answer.getTextContent(),
                    "换实例后必须能从 MySQL 状态恢复并完成回答");
            assertEquals(2, model.requests.size(), "恢复时不得重放首次模型调用");
            System.out.println("[probe] harness suspend->restart->resume on real MySQL OK");
        } finally {
            restarted.close();
        }
    }

    private HarnessAgent harness(ChatModelBase model, Toolkit toolkit) {
        return HarnessAgent.builder()
                .name("p0-mysql-harness")
                .description("Deterministic P0 MySQL harness")
                .sysPrompt("Use only the current request context.")
                .model(model)
                .toolkit(toolkit)
                .stateStore(new JdbcAgentStateStore(dataSource, new MysqlDialect()))
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableFilesystemTools()
                .disableShellTool()
                .disableWorkspaceContext()
                .disableSubagents()
                .disableTranscript()
                .build();
    }

    private Connection rootConnection() throws Exception {
        return DriverManager.getConnection(url(host, ""), user, password);
    }

    private static String url(String host, String database) {
        return "jdbc:mysql://" + host + "/" + database + "?useUnicode=true&characterEncoding=utf8"
                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    }

    /** 与 HarnessP0IsolationTest 相同的确定性模型：首轮发 ToolUse，其后给最终回答。 */
    private static final class ToolCallingModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<List<Msg>> requests = new CopyOnWriteArrayList<>();

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            if (calls.getAndIncrement() == 0) {
                return Flux.just(ChatResponse.builder().id("p0-tool-call")
                        .content(List.of(new ToolUseBlock("tool-use-1", "meeting.reserve", Map.of("room", "A-101"))))
                        .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder().id("p0-final")
                    .content(List.of(TextBlock.builder().text("reservation complete").build()))
                    .finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "haizhuo-p0-mysql-tool-model";
        }
    }
}
