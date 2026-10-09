package com.haizhuo.brain.platform.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrictToolResourcePolicyTest {
    private static final UserId USER = new UserId(17L);

    @Test
    void deniesEveryCapabilityWithoutAnExactResourceFreeDeclaration() {
        StrictToolResourcePolicy policy = new StrictToolResourcePolicy(Set.of());
        CapabilityCatalogEntry capability = capability("note.create", "2");

        assertEquals(null, policy.deriveResource(capability, Map.of("resourceId", "model-value")));
        assertFalse(policy.allows(USER, capability, "note.create", Map.of()));
        assertFalse(policy.allows(USER, capability, StrictToolResourcePolicy.RESOURCE_FREE, Map.of()));
    }

    @Test
    void allowsOnlyTheExactDeclaredRevisionAsResourceFree() {
        StrictToolResourcePolicy policy = new StrictToolResourcePolicy(Set.of("calculation.sum@1.2"));
        CapabilityCatalogEntry declared = capability("calculation.sum", "1.2");
        CapabilityCatalogEntry otherRevision = capability("calculation.sum", "1.3");

        assertEquals(StrictToolResourcePolicy.RESOURCE_FREE, policy.deriveResource(declared, Map.of()));
        assertTrue(policy.allows(USER, declared, StrictToolResourcePolicy.RESOURCE_FREE, Map.of()));
        assertFalse(policy.allows(null, declared, StrictToolResourcePolicy.RESOURCE_FREE, Map.of()));
        assertEquals(null, policy.deriveResource(otherRevision, Map.of()));
        assertFalse(policy.allows(USER, declared, "arbitrary-resource", Map.of()));
    }

    @Test
    void rejectsWildcardOrMalformedDeclarations() {
        assertThrows(IllegalArgumentException.class,
                () -> new StrictToolResourcePolicy(Set.of("calculation.sum@*")));
        assertThrows(IllegalArgumentException.class,
                () -> new StrictToolResourcePolicy(Set.of("calculation.sum")));
    }

    private static CapabilityCatalogEntry capability(String code, String revision) {
        return new CapabilityCatalogEntry(91L, code, CapabilityBinding.CapabilityType.TOOL,
                revision, code, "test capability", "test-tool", "test", "business.action",
                Map.of(), true, false);
    }
}
