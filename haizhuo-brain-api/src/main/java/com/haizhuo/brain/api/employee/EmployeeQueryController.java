package com.haizhuo.brain.api.employee;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.platform.employee.DigitalEmployee;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeQueryController {
    private final EmployeeCatalog employees;
    public EmployeeQueryController(EmployeeCatalog employees) { this.employees = employees; }
    @GetMapping
    public Mono<List<EmployeeResponse>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return Mono.fromCallable(() -> employees.listEnabled(new TenantId(1)).stream().map(EmployeeResponse::from).toList()).subscribeOn(Schedulers.boundedElastic());
    }
    public record EmployeeResponse(long id, String code, String name, String description, boolean available) {
        static EmployeeResponse from(DigitalEmployee employee) { return new EmployeeResponse(employee.id(), employee.code(), employee.displayName(), "数字员工工作助手", employee.enabled()); }
    }
}
