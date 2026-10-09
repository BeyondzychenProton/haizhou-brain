package com.haizhuo.brain.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.haizhuo.brain.api.admin.RuntimeReadinessProvider.GateStatus;
import com.haizhuo.brain.api.admin.RuntimeReadinessProvider.GateState;
import com.haizhuo.brain.api.admin.RuntimeReadinessProvider.ReadinessResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class RuntimeReadinessControllerTest {
    @Test
    void exposesReadOnlyReadinessResponse() {
        ReadinessResponse expected = new ReadinessResponse(1,
                List.of(new GateStatus("channel.real-im", GateState.IMPLEMENTED_CLOSED, false,
                        false, false, "PROVIDER_NOT_SELECTED", "DEPLOYMENT_EVIDENCE_MISSING",
                        List.of("Select provider"), Instant.parse("2026-10-09T03:00:00Z"))), null, false);
        RuntimeReadinessController controller = new RuntimeReadinessController(() -> expected);

        StepVerifier.create(controller.readiness())
                .assertNext(actual -> assertEquals(expected, actual))
                .verifyComplete();
    }
}
