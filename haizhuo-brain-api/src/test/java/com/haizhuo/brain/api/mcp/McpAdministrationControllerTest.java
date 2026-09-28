package com.haizhuo.brain.api.mcp;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.mcp.McpAdministrationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Discovery history is always requested for the trusted administrator principal. */
@ExtendWith(MockitoExtension.class)
class McpAdministrationControllerTest {
    @Mock McpAdministrationService service;
    @Mock AuthenticatedUser actor;

    @Test
    void recentDiffUsesAuthenticatedAdministratorsUserId() {
        var diff = new McpAdministrationService.DiscoveryDiff(null,
                new McpAdministrationService.SnapshotSummary(21, 3,
                        Instant.parse("2026-09-28T12:00:00Z"), 1), List.of());
        when(actor.userId()).thenReturn(new UserId(1001));
        when(service.recentDiff(7, 1001)).thenReturn(diff);

        var response = new McpAdministrationController(service).recentDiff(actor, 7).block();

        assertSame(diff, response);
        verify(service).recentDiff(7, 1001);
    }
}
