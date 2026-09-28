package com.haizhuo.brain.runtime.agentscope.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
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
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * P0 准入缺口的 JDBC 验证（对应《HarnessDurableRuntime-P0验证报告》"尚未完成"项）：
 * AgentState 落在 JDBC（此处用 H2 + 官方 H2Dialect 代替 MySQL + MysqlDialect，方言同源
 * 于 extensions-jdbc），挂起后关闭 Harness、以全新 Harness/Store 实例在同一库上恢复
 * ToolResult 必须得到最终回答。生产 wiring（AgentRuntimeConfiguration）使用同一构造器
 * 形态：{@code new JdbcAgentStateStore(dataSource, new MysqlDialect())}。
 */
class JdbcAgentStateStoreVerificationTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:agentstate_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
        dataSource = h2;
    }

    /**
     * H2 把未加引号的表名存为大写，而方言的 checkTableExists 按小写比对，因此验证环境
     * 必须走 createIfNotExist=true 让 Store 自建表（生产 MySQL 用 2 参构造 + 预先建表，
     * 见 AgentRuntimeConfiguration）。
     */
    private JdbcAgentStateStore newStore() {
        return new JdbcAgentStateStore(dataSource, new H2Dialect(), true);
    }

    @Test
    void jdbcBackedAgentStateCanResumeExternalToolAfterFullRestart() {
        ToolCallingModel model = new ToolCallingModel();
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(ToolSchema.builder().name("meeting.reserve").description("P0 schema")
                .parameters(Map.of("type", "object", "properties", Map.of())).build());
        RuntimeContext context = RuntimeContext.builder().userId("user-a").sessionId("hs-restart").build();

        HarnessAgent first = harness(model, toolkit, newStore());
        try {
            List<io.agentscope.core.event.AgentEvent> events =
                    first.streamEvents("reserve room", context).collectList().block();
            assertTrue(events.stream().anyMatch(RequireExternalExecutionEvent.class::isInstance));
        } finally {
            first.close();
        }

        JdbcAgentStateStore restartedStore = newStore();
        assertTrue(restartedStore.exists("user-a", "hs-restart"),
                "挂起后的 AgentState 必须已落 JDBC，而非仅在进程内");
        assertTrue(restartedStore.listSessionIds("user-a").contains("hs-restart"));

        HarnessAgent restarted = harness(model, toolkit, restartedStore);
        try {
            ToolResultBlock result = ToolResultBlock.builder().id("tool-use-1").name("meeting.reserve")
                    .output(TextBlock.builder().text("room available").build()).build();
            Msg answer = restarted.call(new ToolResultMessage(result), context).block();
            assertEquals("reservation complete", answer.getTextContent(),
                    "换进程（新 Store 实例 + 新 Harness）后必须能从 JDBC 状态恢复并完成回答");
            assertEquals(2, model.requests.size(), "恢复时不得重放首次模型调用");
        } finally {
            restarted.close();
        }
    }

    @Test
    void sessionsAreIsolatedInsideTheSameJdbcStore() {
        JdbcAgentStateStore store = newStore();
        ToolCallingModel model = new ToolCallingModel();
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(ToolSchema.builder().name("meeting.reserve").description("P0 schema")
                .parameters(Map.of("type", "object", "properties", Map.of())).build());

        HarnessAgent harnessA = harness(model, toolkit, store);
        try {
            harnessA.streamEvents("reserve room",
                    RuntimeContext.builder().userId("user-a").sessionId("hs-a").build()).collectList().block();
        } finally {
            harnessA.close();
        }

        assertTrue(store.exists("user-a", "hs-a"));
        assertTrue(!store.exists("user-a", "hs-b"), "状态槽按 (userId, harnessSessionKey) 寻址，互不污染");
        assertTrue(!store.exists("user-b", "hs-a"));
    }

    private static HarnessAgent harness(ChatModelBase model, Toolkit toolkit, JdbcAgentStateStore stateStore) {
        return HarnessAgent.builder()
                .name("p0-jdbc-harness")
                .description("Deterministic P0 JDBC harness")
                .sysPrompt("Use only the current request context.")
                .model(model)
                .toolkit(toolkit)
                .stateStore(stateStore)
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableFilesystemTools()
                .disableShellTool()
                .disableWorkspaceContext()
                .disableSubagents()
                .disableTranscript()
                .build();
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
                    .content(List.of(TextBlock.builder().text("reservation complete").build())).finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "haizhuo-p0-jdbc-tool-model";
        }
    }
}
