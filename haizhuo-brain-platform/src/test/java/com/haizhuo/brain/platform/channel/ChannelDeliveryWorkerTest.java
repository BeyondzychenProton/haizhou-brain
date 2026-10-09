package com.haizhuo.brain.platform.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChannelDeliveryWorkerTest {
    private static final ChannelDelivery DELIVERY = new ChannelDelivery("delivery-1", new RunId("run-1"),
            "binding-1", "provider-1", "private-target", "private body", "run-1:reply");
    private static final ChannelDeliveryClaim CLAIM = new ChannelDeliveryClaim(DELIVERY, 2, 7,
            "private-claim-token");

    @Test
    void senderOutcomeUsesTheExactClaimThatAuthorizedTheSend() {
        FakeOutbox outbox = new FakeOutbox();
        ChannelOutboundSender sender = new ChannelOutboundSender() {
            @Override public String provider() { return "provider-1"; }
            @Override public DeliveryResult send(ChannelDelivery delivery) {
                assertSame(DELIVERY, delivery);
                return new DeliveryResult(DeliveryResult.Status.DELIVERED, "external-1");
            }
        };

        assertTrue(new ChannelDeliveryWorker(outbox, Map.of("provider-1", sender)).deliverNext());

        assertSame(CLAIM, outbox.recordedClaim);
        assertEquals(DeliveryOutcomeWrite.APPLIED, outbox.recordedOutcome);
    }

    @Test
    void missingSenderRecordsPermanentFailureAgainstTheClaim() {
        FakeOutbox outbox = new FakeOutbox();

        assertTrue(new ChannelDeliveryWorker(outbox, Map.of()).deliverNext());

        assertSame(CLAIM, outbox.recordedClaim);
        assertEquals(ChannelOutboundSender.DeliveryResult.Status.PERMANENT_FAILURE, outbox.recordedResult.status());
    }

    @Test
    void senderExceptionRecordsUncertainAgainstTheSameClaim() {
        FakeOutbox outbox = new FakeOutbox();
        ChannelOutboundSender sender = new ChannelOutboundSender() {
            @Override public String provider() { return "provider-1"; }
            @Override public DeliveryResult send(ChannelDelivery delivery) { throw new IllegalStateException("private"); }
        };

        assertTrue(new ChannelDeliveryWorker(outbox, Map.of("provider-1", sender)).deliverNext());

        assertSame(CLAIM, outbox.recordedClaim);
        assertEquals(ChannelOutboundSender.DeliveryResult.Status.UNCERTAIN, outbox.recordedResult.status());
    }

    private static final class FakeOutbox implements ChannelDeliveryOutbox {
        private ChannelDeliveryClaim recordedClaim;
        private ChannelOutboundSender.DeliveryResult recordedResult;
        private DeliveryOutcomeWrite recordedOutcome;

        @Override public Optional<ChannelDeliveryClaim> claimNextDelivery() { return Optional.of(CLAIM); }
        @Override public DeliveryOutcomeWrite recordOutcome(ChannelDeliveryClaim claim,
                                                            ChannelOutboundSender.DeliveryResult result) {
            recordedClaim = claim;
            recordedResult = result;
            recordedOutcome = DeliveryOutcomeWrite.APPLIED;
            return recordedOutcome;
        }
        @Override public ChannelDelivery enqueue(ChannelDelivery delivery) { return delivery; }
    }
}
