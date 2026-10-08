package com.haizhuo.brain.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunRecoveryAdministrationService;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunRecoveryAdministrationControllerTest {
    @Test
    void derivesAuditActorFromAuthenticatedPrincipalAndReturnsSafeSummary() {
        UserId admin = new UserId(9);
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        var service = new RunRecoveryAdministrationService((runId, actor, fence, request, evidence, reason) -> {
            seen.set(actor.value() + ":" + fence + ":" + request + ":" + evidence + ":" + reason);
            return new AgentRun(runId, new SessionId("session-1"), new UserId(42), 1, 1, "request-user",
                    "d".repeat(64), RunState.TERMINATED, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH,
                    RuntimeProfile.SINGLE_SKILLED);
        });
        var controller = new RunRecoveryAdministrationController(service);

        var response = controller.terminate(new AuthenticatedUser(admin, Set.of(PlatformRole.PLATFORM_ADMIN), 1, false),
                "run-1", new RunRecoveryAdministrationController.TerminationRequest(
                        3, "request-admin", "ops-ticket-8", "旧 worker 已停止"))
                .block();

        assertEquals("9:3:request-admin:ops-ticket-8:旧 worker 已停止", seen.get());
        assertEquals("run-1", response.runId());
        assertEquals("TERMINATED", response.state());
        assertEquals("request-admin", response.requestId());
    }
}
