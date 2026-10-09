package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** profile 发布门槛和管理员选项共用的唯一解析结果。 */
public record EmployeeRuntimeProfileSettings(Set<RuntimeProfile> enabledProfiles) {
    public EmployeeRuntimeProfileSettings {
        enabledProfiles = Set.copyOf(enabledProfiles);
        if (!enabledProfiles.contains(RuntimeProfile.LEGACY_STABLE))
            throw new IllegalArgumentException("LEGACY_STABLE must remain enabled");
    }

    public static EmployeeRuntimeProfileSettings parse(String profileNames) {
        Set<RuntimeProfile> profiles = Arrays.stream(profileNames.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(RuntimeProfile::valueOf).collect(Collectors.toUnmodifiableSet());
        return new EmployeeRuntimeProfileSettings(profiles);
    }
}
