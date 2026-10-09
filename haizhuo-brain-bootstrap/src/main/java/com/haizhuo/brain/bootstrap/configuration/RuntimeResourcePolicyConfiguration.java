package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.tool.DefaultToolResourcePolicy;
import com.haizhuo.brain.platform.tool.StrictToolResourcePolicy;
import com.haizhuo.brain.platform.tool.ToolResourcePolicy;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/** 生产环境使用严格资源策略；旧版全放行策略仅可在测试 profile 中显式启用。 */
@Configuration
public class RuntimeResourcePolicyConfiguration {
    @Bean
    @Primary
    ToolResourcePolicy runtimeToolResourcePolicy(Environment environment) {
        String configuredMode = environment.getProperty("haizhuo.brain.tool.resource-policy-mode", "STRICT");
        String mode = configuredMode.trim().toUpperCase(java.util.Locale.ROOT);
        if ("STRICT".equals(mode)) {
            String declared = environment.getProperty("haizhuo.brain.tool.resource-free-capabilities", "");
            Set<String> resourceFree = Arrays.stream(declared.split(","))
                    .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
            return new StrictToolResourcePolicy(resourceFree);
        }
        boolean isolatedTestProfile = Arrays.equals(environment.getActiveProfiles(), new String[] {"test"});
        if ("LEGACY_CAPABILITY_ONLY".equals(mode) && isolatedTestProfile) {
            return new DefaultToolResourcePolicy();
        }
        throw new IllegalStateException("resource policy mode must be STRICT outside an explicit test profile");
    }
}
