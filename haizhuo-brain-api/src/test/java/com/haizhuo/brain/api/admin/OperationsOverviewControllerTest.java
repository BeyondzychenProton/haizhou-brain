package com.haizhuo.brain.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.haizhuo.brain.api.admin.OperationsOverviewProvider.OverviewResponse;
import com.haizhuo.brain.api.admin.OperationsOverviewProvider.ObservabilitySummary;
import com.haizhuo.brain.api.admin.OperationsOverviewProvider.RuntimeSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class OperationsOverviewControllerTest {
    @Test
    void exposesTheReadOnlyProviderSnapshot() {
        OverviewResponse expected = new OverviewResponse(Instant.parse("2026-10-09T03:00:00Z"),
                "prod-a", new RuntimeSummary("2.0.3", true, Map.of("run-worker", true)),
                List.of(), new ObservabilitySummary(false, false, false, null,
                "NOT_CONFIGURED", null, null), List.of());
        OperationsOverviewController controller = new OperationsOverviewController(() -> expected);

        StepVerifier.create(controller.overview())
                .assertNext(actual -> assertEquals(expected, actual))
                .verifyComplete();
    }
}
