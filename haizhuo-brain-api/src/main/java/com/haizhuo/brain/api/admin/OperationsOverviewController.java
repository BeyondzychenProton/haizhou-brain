package com.haizhuo.brain.api.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 只读运维事实；访问受现有 /api/admin/** 安全规则限制。 */
@RestController
@RequestMapping("/api/admin/v1/operations")
public class OperationsOverviewController {
    private final OperationsOverviewProvider provider;

    public OperationsOverviewController(OperationsOverviewProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/overview")
    public Mono<OperationsOverviewProvider.OverviewResponse> overview() {
        return Mono.fromSupplier(provider::overview);
    }
}
