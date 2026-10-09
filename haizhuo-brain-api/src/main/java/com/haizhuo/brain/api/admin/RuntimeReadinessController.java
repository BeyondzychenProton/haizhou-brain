package com.haizhuo.brain.api.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 只读运行门槛；现有 /api/admin/** 安全规则要求平台管理员身份。 */
@RestController
@RequestMapping("/api/admin/v1/runtime")
public class RuntimeReadinessController {
    private final RuntimeReadinessProvider provider;

    public RuntimeReadinessController(RuntimeReadinessProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/readiness")
    public Mono<RuntimeReadinessProvider.ReadinessResponse> readiness() {
        return Mono.fromSupplier(provider::readiness);
    }
}
