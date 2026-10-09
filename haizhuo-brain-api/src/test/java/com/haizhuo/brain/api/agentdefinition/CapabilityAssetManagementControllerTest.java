package com.haizhuo.brain.api.agentdefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.haizhuo.brain.api.error.ApiError;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityAssetDraft;
import com.haizhuo.brain.platform.employee.CapabilityAssetManagementService;
import com.haizhuo.brain.platform.employee.CapabilityAssetNotFoundException;
import com.haizhuo.brain.platform.employee.CapabilityAssetRepository;
import com.haizhuo.brain.platform.employee.CapabilityAssetRevision;
import com.haizhuo.brain.platform.employee.CapabilityAssetRevisionPage;
import com.haizhuo.brain.platform.employee.CapabilityAssetSummary;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityAssetManagementControllerTest {
    private static final String CODE = "skill.customer-analysis";
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    @Test
    void revisionHistoryPassesAssetCursorAndBoundedPageToService() {
        FakeRepository repository = new FakeRepository();
        CapabilityAssetManagementController controller = new CapabilityAssetManagementController(service(repository));

        CapabilityAssetRevisionPage page = controller.revisions(CODE, "cursor-1", 20).block(Duration.ofSeconds(2));

        assertEquals(new CapabilityAssetRevisionPage(List.of(), "next-cursor", true), page);
        assertEquals(CODE, repository.listedCapabilityCode);
        assertEquals("cursor-1", repository.listedCursor);
        assertEquals(20, repository.listedLimit);
    }

    @Test
    void publishUsesAuthenticatedAdministratorAsAuditActor() {
        FakeRepository repository = new FakeRepository();
        CapabilityAssetManagementController controller = new CapabilityAssetManagementController(service(repository));
        AuthenticatedUser administrator = new AuthenticatedUser(new UserId(71),
                Set.of(PlatformRole.PLATFORM_ADMIN), 3, false);

        CapabilityAssetRevision revision = controller.publish(administrator, CODE,
                new CapabilityAssetManagementController.PublishRequest(1, "publish-1", "发布修订"))
                .block(Duration.ofSeconds(2));

        assertEquals(71, repository.publishedActorId);
        assertEquals("publish-1", revision.publishRequestId());
        assertEquals(71, revision.reviewedBy());
    }

    @Test
    void missingAssetMapsToSafeNotFoundBody() {
        ApiError response = new CapabilityAssetManagementController(service(new FakeRepository()))
                .notFound(new CapabilityAssetNotFoundException()).block(Duration.ofSeconds(2));

        assertEquals(new ApiError("RESOURCE_NOT_FOUND", "Capability asset was not found"), response);
    }

    private static CapabilityAssetManagementService service(FakeRepository repository) {
        return new CapabilityAssetManagementService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class FakeRepository implements CapabilityAssetRepository {
        private String listedCapabilityCode;
        private String listedCursor;
        private int listedLimit;
        private long publishedActorId;

        @Override public List<CapabilityAssetSummary> listAssetSummaries() { return List.of(); }

        @Override public boolean assetExists(String capabilityCode) { return true; }

        @Override
        public CapabilityAssetRevisionPage listRevisionSummaries(String capabilityCode, String cursor, int limit) {
            listedCapabilityCode = capabilityCode;
            listedCursor = cursor;
            listedLimit = limit;
            return new CapabilityAssetRevisionPage(List.of(), "next-cursor", true);
        }

        @Override public Optional<CapabilityAssetDraft> findDraft(String capabilityCode) { return Optional.empty(); }

        @Override public Optional<CapabilityAssetRevision> findRevision(String capabilityCode, long revisionId) {
            return Optional.empty();
        }

        @Override
        public CapabilityAssetDraft saveDraft(CapabilityAssetDraft draft, int expectedDraftRevision, String reason) {
            throw new UnsupportedOperationException("Not used by this controller contract test");
        }

        @Override
        public CapabilityAssetRevision publish(String capabilityCode, int expectedDraftRevision, String requestId,
                                               long actorId, String reason) {
            publishedActorId = actorId;
            return new CapabilityAssetRevision(101, capabilityCode, CapabilityBinding.CapabilityType.SKILL,
                    "1.0.0", "Customer Analysis", "Test asset", List.of(), "{}", "test-hash", requestId,
                    actorId, NOW);
        }
    }
}
