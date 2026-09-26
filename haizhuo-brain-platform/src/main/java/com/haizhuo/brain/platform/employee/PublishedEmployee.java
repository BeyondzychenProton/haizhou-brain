package com.haizhuo.brain.platform.employee;

import java.util.List;

public record PublishedEmployee(DigitalEmployee employee, AgentDefinitionVersion definition,
                                List<CapabilityBinding> capabilities) {
    public PublishedEmployee {
        capabilities = List.copyOf(capabilities);
    }
}
