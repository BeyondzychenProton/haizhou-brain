package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 未接入业务资源授权器时使用的 fail-closed 资源策略。
 * 只有明确声明无需资源授权的精确能力修订才能通过。
 */
public final class StrictToolResourcePolicy implements ToolResourcePolicy {
    public static final String RESOURCE_FREE = "RESOURCE_FREE";
    private static final Pattern DECLARATION = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,127}@[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");

    private final Set<String> resourceFreeCapabilityRevisions;

    public StrictToolResourcePolicy(Set<String> resourceFreeCapabilityRevisions) {
        Objects.requireNonNull(resourceFreeCapabilityRevisions, "resourceFreeCapabilityRevisions");
        if (resourceFreeCapabilityRevisions.stream().anyMatch(value -> value == null
                || !DECLARATION.matcher(value).matches())) {
            throw new IllegalArgumentException("resource-free declaration must be an exact capabilityCode@revision");
        }
        this.resourceFreeCapabilityRevisions = Set.copyOf(resourceFreeCapabilityRevisions);
    }

    @Override
    public String deriveResource(CapabilityCatalogEntry capability, java.util.Map<String, Object> arguments) {
        return isDeclaredResourceFree(capability) ? RESOURCE_FREE : null;
    }

    @Override
    public boolean allows(UserId userId, CapabilityCatalogEntry capability, String resource,
                          java.util.Map<String, Object> arguments) {
        return userId != null && RESOURCE_FREE.equals(resource) && isDeclaredResourceFree(capability);
    }

    public boolean hasResourceFreeDeclaration(CapabilityCatalogEntry capability) {
        return isDeclaredResourceFree(capability);
    }

    private boolean isDeclaredResourceFree(CapabilityCatalogEntry capability) {
        return capability != null && resourceFreeCapabilityRevisions.contains(
                capability.capabilityCode() + "@" + capability.revision());
    }
}
