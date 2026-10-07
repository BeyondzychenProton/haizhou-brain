package com.haizhuo.brain.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.*;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.*;
import com.haizhuo.brain.infrastructure.session.*;
import com.haizhuo.brain.kernel.identity.*;
import com.haizhuo.brain.platform.channel.*;
import com.haizhuo.brain.platform.employee.*;
import com.haizhuo.brain.platform.employee.runtime.*;
import com.haizhuo.brain.platform.run.*;
import com.haizhuo.brain.platform.session.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ChannelFixedSessionVersionTest {
    @Test
    void waitingMessageKeepsSessionVersionAfterPublication() {
        var jdbc = newJdbc("channel_fixed_version");
        createRunAndToolTables(jdbc);
        createSessionAndGuidanceTables(jdbc);
        createSessionEventTable(jdbc);
        createChannelTables(jdbc);
        jdbc.update("INSERT INTO platform_channel_account(binding_id,tenant_id,provider,external_account_key,"
                + "credential_ref,default_employee_id,enabled,created_at,updated_at)"
                + " VALUES('main',1,'feishu','account','ref',1,TRUE,?,?)",
                java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
        AtomicLong version = new AtomicLong(1);
        EmployeeCatalog employees = (tenant, employee) -> Optional.of(new PublishedEmployee(
                new DigitalEmployee(employee, tenant, "employee", "员工", true),
                new AgentDefinitionVersion(version.get(), employee, (int)version.get(), "指令", "openai", "model", T0), List.of()));
        HarnessDefinitionBundleRepository bundles = new HarnessDefinitionBundleRepository() {
            public HarnessDefinitionBundle save(HarnessDefinitionBundle b) { return b; }
            public Optional<HarnessDefinitionBundle> findByBundleHash(String hash) { return Optional.empty(); }
            public Optional<HarnessDefinitionBundle> findByDefinitionVersionId(long id) {
                return Optional.of(new HarnessDefinitionBundle(id, id, "员工", "指令", "openai", "model", 3,
                        "{}", "workspace", List.of(), "tools", "{}", "{}", "bundle-" + id, T0));
            }
        };
        var store = new JdbcSessionRunStore(jdbc, new JdbcSessionEventProjector(jdbc));
        var sessions = new SessionApplicationService(store, employees, bundles, new HarnessRunSpecFactory(null, null), Clock.fixed(T0, ZoneOffset.UTC));
        var inbound = new JdbcChannelTurnStore(jdbc, sessions, Clock.fixed(T0, ZoneOffset.UTC));
        var binding = new ChannelAccountBinding("main", new TenantId(1), "feishu", "account", "ref", 1,
                SessionScope.defaultScope(), true);
        var owner = new UserId(42);
        var first = inbound.accept(message("event-1", "peer-1"), binding, owner);
        var waiting = inbound.accept(message("event-2", "peer-1"), binding, owner);
        assertEquals(first.sessionId(), waiting.sessionId());
        assertTrue(waiting.runId().isEmpty());
        version.set(2);
        jdbc.update("UPDATE platform_agent_run SET state='SUCCEEDED' WHERE run_id=?", first.runId().orElseThrow().value());
        new JdbcChannelTurnPromoter(jdbc, sessions).promoteWaitingTurn(first.sessionId());
        String nextRun = jdbc.queryForObject("SELECT run_id FROM platform_channel_inbox WHERE provider_event_id='event-2'", String.class);
        assertEquals(1, store.findRun(new RunId(nextRun), owner).orElseThrow().definitionVersionId());
        assertFalse(sessions.get(first.sessionId(), owner).legacyRuntime());
        var newConversation = inbound.accept(message("event-3", "peer-2"), binding, owner);
        assertEquals(2L, sessions.get(newConversation.sessionId(), owner).definitionVersionId());
    }
    private static VerifiedChannelMessage message(String event, String peer) {
        return new VerifiedChannelMessage("main", "feishu", event, peer, "user-42", "你好", peer);
    }
}
