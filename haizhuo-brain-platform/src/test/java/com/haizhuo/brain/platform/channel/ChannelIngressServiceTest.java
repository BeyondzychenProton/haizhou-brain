package com.haizhuo.brain.platform.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.AgentDefinitionVersion;
import com.haizhuo.brain.platform.employee.DigitalEmployee;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChannelIngressServiceTest {
    private static final TenantId TENANT = new TenantId(7);
    private static final ChannelAccountBinding BINDING =
            new ChannelAccountBinding("feishu-main", TENANT, "feishu", "cli_app", "env:feishu-main", 11, true);

    @Test
    void providerMismatchCannotReachInbox() {
        AtomicInteger writes = new AtomicInteger();
        ChannelIngressService service = service(published(TENANT), writes);

        assertThrows(IllegalArgumentException.class,
                () -> service.accept(message("wecom")));
        assertEquals(0, writes.get());
    }

    @Test
    void catalogCannotRouteAnotherTenantsEmployee() {
        AtomicInteger writes = new AtomicInteger();
        ChannelIngressService service = service(published(new TenantId(8)), writes);

        assertThrows(IllegalStateException.class,
                () -> service.accept(message("feishu")));
        assertEquals(0, writes.get());
    }

    @Test
    void acceptedTurnUsesServerBindingAndResolvedUser() {
        AtomicInteger writes = new AtomicInteger();
        ChannelIngressService service = service(published(TENANT), writes);

        ChannelAcceptance accepted = service.accept(message("feishu"));

        assertEquals(new SessionId("platform-session"), accepted.sessionId());
        assertEquals(Optional.of(new RunId("platform-run")), accepted.runId());
        assertEquals(1, writes.get());
    }

    private static ChannelIngressService service(PublishedEmployee employee, AtomicInteger writes) {
        ChannelAccountDirectory accounts = id -> Optional.of(BINDING);
        ChannelIdentityDirectory identities = (tenant, binding, externalUser) -> Optional.of(new UserId(23));
        EmployeeCatalog employees = (tenant, employeeId) -> Optional.of(employee);
        ChannelTurnStore turns = (message, binding, user) -> {
            assertEquals(BINDING, binding);
            assertEquals(new UserId(23), user);
            writes.incrementAndGet();
            return new ChannelAcceptance(new SessionId("platform-session"),
                    Optional.of(new RunId("platform-run")), false);
        };
        return new ChannelIngressService(accounts, identities, employees, turns);
    }

    private static PublishedEmployee published(TenantId tenantId) {
        return new PublishedEmployee(new DigitalEmployee(11, tenantId, "assistant", "助手", true),
                new AgentDefinitionVersion(91, 11, 1, "instructions", "dashscope", "qwen-plus", Instant.now()),
                List.of());
    }

    private static VerifiedChannelMessage message(String provider) {
        return new VerifiedChannelMessage("feishu-main", provider, "event-1", "chat-1", "ou-1",
                "hello", "chat-1");
    }
}
